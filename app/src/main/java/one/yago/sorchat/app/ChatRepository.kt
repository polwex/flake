package one.yago.sorchat.app

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    /** Photos and files, sent and received. */
    private val mediaDir = File(context.filesDir, "media").apply { mkdirs() }

    private val _transfers = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Upload/download progress (0..1) of attachments in flight, by message id. */
    val transfers: StateFlow<Map<String, Float>> = _transfers.asStateFlow()

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
            mediaDir.listFiles()?.forEach(File::delete)
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
        val file = File(voiceDir, "${UUID.randomUUID()}.ogg")
        withContext(Dispatchers.IO) {
            recording.copyTo(file)
            recording.delete()
        }
        sendPrepared(peer, PreparedMedia(file, VOICE_MIME_TYPE, null), durationMs = durationMs)
    }

    /** Sends a photo from the gallery or camera, shrunk and compressed first. */
    suspend fun sendPhoto(peer: String, uri: Uri) = sendPrepared(peer, preparePhoto(context.contentResolver, uri, mediaDir))

    /** Sends any file, as is. */
    suspend fun sendFile(peer: String, uri: Uri) = sendPrepared(peer, prepareFile(context.contentResolver, uri, mediaDir))

    private suspend fun sendPrepared(peer: String, media: PreparedMedia, durationMs: Long? = null) {
        val message = ChatMessage(
            UUID.randomUUID().toString(), peer, fromMe = true, body = "", System.currentTimeMillis(), MessageStatus.SENDING,
            localPath = media.file.path, mimeType = media.mimeType, durationMs = durationMs,
            fileName = media.name, fileSize = media.file.length(), width = media.width, height = media.height,
        )
        dao.insertMessage(message)
        sounds.messageSent()
        deliver(message)
    }

    /** Fetches a received attachment the user tapped (it wasn't downloaded automatically). */
    fun requestDownload(messageId: String) = DownloadWorker.enqueue(context, messageId)

    /** Called by [DownloadWorker]. Throws on errors worth retrying. */
    suspend fun downloadAttachment(messageId: String) {
        val message = dao.message(messageId) ?: return
        if (message.localPath != null || message.unavailable) return
        val me = identity.value ?: return
        val mediaId = message.mediaId ?: return
        val target = downloadTarget(mediaDir, message)
        try {
            if (client.download(me.token, mediaId, target) { setProgress(messageId, it) }) {
                dao.setLocalPath(messageId, target.path)
                // Not fatal: the server forgets the file after a while anyway.
                runCatching { client.confirmDownload(me.token, mediaId) }
            } else {
                dao.setUnavailable(messageId)
            }
        } finally {
            setProgress(messageId, null)
        }
    }

    /** Photos, voice notes and small files download right away; big files on mobile data wait for a tap. */
    private fun maybeAutoDownload(message: ChatMessage) {
        val small = (message.fileSize ?: 0) <= AUTO_DOWNLOAD_BYTES
        val metered = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        if (message.isVoice || message.isImage || small || !metered) DownloadWorker.enqueue(context, message.id)
    }

    /** Throttled to whole percents, so the UI isn't flooded. */
    private fun setProgress(messageId: String, progress: Float?) {
        _transfers.update { current ->
            when {
                progress == null -> current - messageId
                ((current[messageId] ?: -1f) * 100).toInt() == (progress * 100).toInt() -> current
                else -> current + (messageId to progress)
            }
        }
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
                mediaId = client.upload(me.token, File(path), message.mimeType!!) { setProgress(message.id, it) }
                dao.setMediaId(message.id, mediaId)
            } catch (e: UploadRejectedException) {
                // Too big or no room on the server: retrying won't help.
                dao.setStatus(message.id, MessageStatus.FAILED)
                notice.value = e.message
                return
            } catch (e: Exception) {
                Log.w(TAG, "Upload of ${message.id} failed", e)
                return
            } finally {
                uploading.remove(message.id)
                setProgress(message.id, null)
            }
        }
        val attachment = mediaId?.let {
            Attachment(it, message.mimeType!!, File(path!!).length(), message.durationMs, message.fileName, message.width, message.height)
        }
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
                    val message = receivedMessage(frame)
                    dao.insertMessage(message)
                    if (message.mediaId != null) maybeAutoDownload(message)
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

    /** The local copy of an incoming message. Its attachment, if any, is downloaded separately. */
    private fun receivedMessage(frame: ServerFrame.Message): ChatMessage {
        val a = frame.attachment
        return ChatMessage(
            frame.id, frame.from, fromMe = false, frame.body, frame.sentAt, MessageStatus.RECEIVED,
            mediaId = a?.mediaId, mimeType = a?.mimeType, durationMs = a?.durationMs,
            fileName = a?.name, fileSize = a?.size, width = a?.width, height = a?.height,
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
            mediaDir.listFiles()?.forEach(File::delete)
        }
        _identity.value = null
        notice.value = "The server no longer knows this account. Please register again."
    }

    companion object {
        private const val TAG = "ChatRepository"
        private const val VOICE_MIME_TYPE = "audio/ogg"
        private const val AUTO_DOWNLOAD_BYTES = 5L * 1024 * 1024

        @Volatile
        private var instance: ChatRepository? = null

        fun get(context: Context): ChatRepository = instance ?: synchronized(this) {
            instance ?: ChatRepository(context.applicationContext).also { instance = it }
        }
    }
}
