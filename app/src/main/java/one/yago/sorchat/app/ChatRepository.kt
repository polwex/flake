package one.yago.sorchat.app

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.yago.sorchat.protocol.Attachment
import one.yago.sorchat.protocol.ClientFrame
import one.yago.sorchat.protocol.ServerFrame
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * App-wide owner of the account, local database and server connection. Shared by the UI
 * (through [ChatViewModel]) and [PushService], which may run without any UI.
 */
class ChatRepository private constructor(private val context: Context) {
    private val prefs = Prefs(context)
    private val db = ChatDatabase.get(context)
    val dao = db.dao()
    private val client = ChatClient(BuildConfig.SERVER_URL)
    private val passkeys = Passkeys(client)
    private val sounds = Sounds(context)
    private val notifications = Notifications(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val voiceDir = File(context.filesDir, "voice").apply { mkdirs() }

    /** Outgoing messages whose attachment is being uploaded, so a resend doesn't upload twice. */
    private val uploading: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _identity = MutableStateFlow(prefs.identity)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()
    val connected: StateFlow<Boolean> = client.connected

    val calls = CallManager(
        context,
        scope,
        sounds,
        sendSignal = { to, callId, signal -> client.send(ClientFrame.Call(to, callId, signal)) },
        iceServers = { client.iceServers(checkNotNull(identity.value).token) },
    )

    private val _hasPasskey = MutableStateFlow(prefs.hasPasskey)
    val hasPasskey: StateFlow<Boolean> = _hasPasskey.asStateFlow()

    /** Something the user should be told about that happened outside the UI's control. */
    val notice = MutableStateFlow<String?>(null)

    /** Only one connection at a time: either the live one, or a short sync after a push. */
    private val connectionLock = Mutex()
    private var live: Job? = null
    private var foreground = false

    /** The conversation currently on screen; its incoming messages don't raise notifications. */
    @Volatile
    var visibleChat: String? = null
        set(value) {
            field = value
            value?.let(notifications::cancel)
        }

    init {
        // Calls need the signaling connection even if the app goes to the background mid-call.
        scope.launch { calls.call.collect { updateConnection() } }
    }

    @Synchronized
    fun setForeground(value: Boolean) {
        foreground = value
        updateConnection()
    }

    @Synchronized
    private fun updateConnection() {
        val me = identity.value
        if ((foreground || calls.inCall) && me != null) {
            if (live?.isActive != true) live = scope.launch { runLive(me) }
        } else {
            live?.cancel()
            live = null
        }
    }

    suspend fun register(name: String) {
        val response = client.register(name)
        val me = Identity(response.userId, name, response.token)
        prefs.identity = me
        prefs.registeredPushToken = null
        setHasPasskey(false)
        notice.value = null
        _identity.value = me
        updateConnection()
    }

    /** Adds a passkey to the current account. Throws [PasskeyCancelledException] if dismissed. */
    suspend fun createPasskey(activity: Activity) {
        val me = checkNotNull(identity.value)
        passkeys.create(activity, me.token)
        setHasPasskey(true)
    }

    /** Signs in to an existing account with a passkey, e.g. after reinstalling or on a new phone. */
    suspend fun signInWithPasskey(activity: Activity) {
        val login = passkeys.signIn(activity)
        // Whatever was stored locally belonged to whichever account was here before.
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            voiceDir.listFiles()?.forEach(File::delete)
        }
        val me = Identity(login.userId, login.name, login.token)
        prefs.identity = me
        prefs.registeredPushToken = null
        setHasPasskey(true)
        notice.value = null
        _identity.value = me
        updateConnection()
    }

    private fun setHasPasskey(value: Boolean) {
        prefs.hasPasskey = value
        _hasPasskey.value = value
    }

    /** Returns false if there's no user with this id. */
    suspend fun addContact(id: String): Boolean {
        val me = identity.value ?: return false
        val info = client.lookUp(me.token, id) ?: return false
        dao.insertContact(Contact(info.id, info.name))
        return true
    }

    suspend fun send(peer: String, body: String) {
        val message = ChatMessage(UUID.randomUUID().toString(), peer, fromMe = true, body, System.currentTimeMillis(), MessageStatus.SENDING)
        // Stored first, so it survives the app being killed and is resent by resendPending().
        dao.insertMessage(message)
        sounds.messageSent()
        deliver(message)
    }

    /** Where a new recording should be written before it's sent. */
    fun newRecordingFile(): File = File(context.cacheDir, "recording-${UUID.randomUUID()}.ogg")

    suspend fun sendVoice(peer: String, recording: File, durationMs: Long) {
        val id = UUID.randomUUID().toString()
        val file = File(voiceDir, "$id.ogg")
        withContext(Dispatchers.IO) {
            recording.copyTo(file)
            recording.delete()
        }
        val message = ChatMessage(
            id, peer, fromMe = true, body = "", System.currentTimeMillis(), MessageStatus.SENDING,
            localPath = file.path, mimeType = VOICE_MIME_TYPE, durationMs = durationMs,
        )
        dao.insertMessage(message)
        sounds.messageSent()
        deliver(message)
    }

    /** Called from [PushService]: connect just long enough to fetch what's waiting. */
    suspend fun syncFromPush() {
        val me = identity.value ?: return
        // If the live connection is up, the messages arrive over it anyway.
        if (!connectionLock.tryLock()) return
        try {
            withTimeout(15.seconds) {
                client.session(me.token, onConnected = ::resendPending) { frame ->
                    handle(frame)
                    if (frame is ServerFrame.Synced) client.disconnect()
                }
            }
        } catch (_: UnauthorizedException) {
            resetAccount()
        } catch (e: Exception) {
            Log.w(TAG, "Sync after push failed", e)
        } finally {
            connectionLock.unlock()
        }
    }

    fun onPushToken(token: String) {
        prefs.pushToken = token
        identity.value?.let { me -> scope.launch { registerPushToken(me) } }
    }

    private suspend fun runLive(me: Identity) {
        try {
            connectionLock.withLock {
                client.run(
                    me.token,
                    onConnected = {
                        resendPending()
                        scope.launch { registerPushToken(me) }
                    },
                    onFrame = ::handle,
                )
            }
        } catch (_: UnauthorizedException) {
            resetAccount()
        }
    }

    /**
     * Sends a stored outgoing message, uploading its attachment first if that hasn't happened yet.
     * If anything fails it stays SENDING and is retried by [resendPending] on the next connection.
     */
    private suspend fun deliver(message: ChatMessage) {
        val me = identity.value ?: return
        var mediaId = message.mediaId
        val path = message.localPath
        if (path != null && mediaId == null) {
            if (!uploading.add(message.id)) return
            try {
                mediaId = client.upload(me.token, File(path), message.mimeType!!)
                dao.setMediaId(message.id, mediaId)
            } catch (e: Exception) {
                Log.w(TAG, "Upload of ${message.id} failed", e)
                return
            } finally {
                uploading.remove(message.id)
            }
        }
        val attachment = mediaId?.let { Attachment(it, message.mimeType!!, File(path!!).length(), message.durationMs) }
        client.send(ClientFrame.Send(message.id, message.peer, message.body, attachment))
    }

    /** The server dedupes by message id, so resending something it already has is harmless. */
    private fun resendPending() {
        // In the background: uploads can take a while and mustn't hold up incoming frames.
        scope.launch { for (m in dao.pending()) deliver(m) }
    }

    private suspend fun handle(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Message -> {
                val sender = Contact(frame.from, frame.fromName)
                dao.insertContact(sender)
                val isNew = dao.message(frame.id) == null
                if (isNew) {
                    // An exception here (e.g. the download failing) skips the ack, so the server redelivers it.
                    dao.insertMessage(receivedMessage(frame))
                }
                // Only ack once it's on disk: the server deletes its copy on ack.
                // Duplicates are acked too; it means the server's earlier copy wasn't deleted yet.
                client.send(ClientFrame.Ack(frame.id))
                if (isNew && foreground && visibleChat == frame.from) {
                    // Looking at this chat: no notification, just a soft chime.
                    sounds.messageReceived()
                } else if (isNew) {
                    dao.message(frame.id)?.let { notifications.showMessage(it, sender) }
                }
            }
            is ServerFrame.Accepted -> dao.setStatus(frame.id, MessageStatus.SENT)
            is ServerFrame.Call -> calls.onSignal(frame)
            is ServerFrame.Synced -> Unit
            is ServerFrame.Error -> {
                val id = frame.id
                if (id != null) dao.setStatus(id, MessageStatus.FAILED)
                else notice.value = frame.reason
            }
        }
    }

    /** Builds the local copy of an incoming message, downloading its attachment first. */
    private suspend fun receivedMessage(frame: ServerFrame.Message): ChatMessage {
        val attachment = frame.attachment
        var localPath: String? = null
        if (attachment != null) {
            val me = checkNotNull(identity.value)
            // Named locally rather than after the sender-chosen message id, which could contain "../".
            val file = File(voiceDir, "${UUID.randomUUID()}.ogg")
            if (client.download(me.token, attachment.mediaId, file)) localPath = file.path
        }
        return ChatMessage(
            frame.id, frame.from, fromMe = false, frame.body, frame.sentAt, MessageStatus.RECEIVED,
            localPath = localPath, mediaId = attachment?.mediaId, mimeType = attachment?.mimeType, durationMs = attachment?.durationMs,
        )
    }

    private suspend fun registerPushToken(me: Identity) {
        try {
            val token = prefs.pushToken
            if (token == null) {
                // The token is delivered to PushService.onRegistered, which calls back into onPushToken().
                FirebaseMessaging.getInstance().register().await()
                return
            }
            if (token == prefs.registeredPushToken) return
            client.registerPushToken(me.token, token)
            prefs.registeredPushToken = token
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't register push token", e)
        }
    }

    private suspend fun resetAccount() {
        prefs.identity = null
        prefs.registeredPushToken = null
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            voiceDir.listFiles()?.forEach(File::delete)
        }
        _identity.value = null
        notice.value = "The server no longer knows this account. Please register again."
    }

    companion object {
        private const val TAG = "ChatRepository"
        private const val VOICE_MIME_TYPE = "audio/ogg"

        @Volatile
        private var instance: ChatRepository? = null

        fun get(context: Context): ChatRepository = instance ?: synchronized(this) {
            instance ?: ChatRepository(context.applicationContext).also { instance = it }
        }
    }
}
