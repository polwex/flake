package one.yago.sorchat.app

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
import one.yago.sorchat.protocol.ClientFrame
import one.yago.sorchat.protocol.ServerFrame
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * App-wide owner of the account, local database and server connection. Shared by the UI
 * (through [ChatViewModel]) and [PushService], which may run without any UI.
 */
class ChatRepository private constructor(context: Context) {
    private val prefs = Prefs(context)
    private val db = ChatDatabase.get(context)
    val dao = db.dao()
    private val client = ChatClient(BuildConfig.SERVER_URL)
    private val notifications = Notifications(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _identity = MutableStateFlow(prefs.identity)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()
    val connected: StateFlow<Boolean> = client.connected

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

    @Synchronized
    fun setForeground(value: Boolean) {
        foreground = value
        updateConnection()
    }

    @Synchronized
    private fun updateConnection() {
        val me = identity.value
        if (foreground && me != null) {
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
        _identity.value = me
        updateConnection()
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
        client.send(ClientFrame.Send(message.id, peer, body))
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

    /** The server dedupes by message id, so resending something it already has is harmless. */
    private suspend fun resendPending() {
        for (m in dao.pending()) client.send(ClientFrame.Send(m.id, m.peer, m.body))
    }

    private suspend fun handle(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Message -> {
                val sender = Contact(frame.from, frame.fromName)
                dao.insertContact(sender)
                val message = ChatMessage(frame.id, frame.from, fromMe = false, frame.body, frame.sentAt, MessageStatus.RECEIVED)
                val isNew = dao.insertMessage(message) != -1L
                // Only ack once it's on disk: the server deletes its copy on ack.
                // Duplicates are acked too; it means the server's earlier copy wasn't deleted yet.
                client.send(ClientFrame.Ack(frame.id))
                if (isNew && !(foreground && visibleChat == frame.from)) notifications.showMessage(message, sender)
            }
            is ServerFrame.Accepted -> dao.setStatus(frame.id, MessageStatus.SENT)
            is ServerFrame.Synced -> Unit
            is ServerFrame.Error -> {
                val id = frame.id
                if (id != null) dao.setStatus(id, MessageStatus.FAILED)
                else notice.value = frame.reason
            }
        }
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
        withContext(Dispatchers.IO) { db.clearAllTables() }
        _identity.value = null
        notice.value = "The server no longer knows this account. Please register again."
    }

    companion object {
        private const val TAG = "ChatRepository"

        @Volatile
        private var instance: ChatRepository? = null

        fun get(context: Context): ChatRepository = instance ?: synchronized(this) {
            instance ?: ChatRepository(context.applicationContext).also { instance = it }
        }
    }
}
