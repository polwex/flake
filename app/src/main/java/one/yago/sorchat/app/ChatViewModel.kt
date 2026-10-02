package one.yago.sorchat.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.yago.sorchat.protocol.ClientFrame
import one.yago.sorchat.protocol.ServerFrame
import java.util.UUID

enum class MessageStatus { SENDING, SENT, FAILED, RECEIVED }

data class ChatMessage(
    val id: String,
    val fromMe: Boolean,
    val body: String,
    val sentAt: Long,
    val status: MessageStatus,
)

data class UiState(
    val me: Identity? = null,
    val contacts: List<Contact> = emptyList(),
    /** Conversations keyed by contact id, oldest message first. */
    val messages: Map<String, List<ChatMessage>> = emptyMap(),
    val openChat: String? = null,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val client = ChatClient(BuildConfig.SERVER_URL)
    private var connection: Job? = null

    private val _state = MutableStateFlow(UiState(me = prefs.identity, contacts = prefs.contacts))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { client.connected.collect { c -> _state.update { it.copy(connected = c) } } }
        state.value.me?.let(::connect)
    }

    fun register(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { client.register(trimmed) }
                .onSuccess { response ->
                    val me = Identity(response.userId, trimmed, response.token)
                    prefs.identity = me
                    _state.update { it.copy(me = me, busy = false) }
                    connect(me)
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = "Couldn't register: ${e.message}") } }
        }
    }

    fun addContact(rawId: String) {
        val me = state.value.me ?: return
        val id = rawId.trim().lowercase()
        if (id.isEmpty() || id == me.id || state.value.contacts.any { it.id == id }) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { client.lookUp(me.token, id) }
                .onSuccess { info ->
                    if (info == null) _state.update { it.copy(busy = false, error = "No user with ID \"$id\"") }
                    else {
                        saveContact(Contact(info.id, info.name))
                        _state.update { it.copy(busy = false) }
                    }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = "Couldn't add contact: ${e.message}") } }
        }
    }

    fun openChat(contactId: String?) = _state.update { it.copy(openChat = contactId) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun send(text: String) {
        val peer = state.value.openChat ?: return
        val body = text.trim().ifEmpty { return }
        val message = ChatMessage(UUID.randomUUID().toString(), fromMe = true, body, System.currentTimeMillis(), MessageStatus.SENDING)
        addMessage(peer, message)
        // If we're offline, it's sent from resendPending() once the connection is back.
        client.send(ClientFrame.Send(message.id, peer, body))
    }

    private fun connect(me: Identity) {
        connection?.cancel()
        connection = viewModelScope.launch {
            try {
                client.run(me.token, onConnected = ::resendPending, onFrame = ::handle)
            } catch (_: UnauthorizedException) {
                prefs.identity = null
                _state.update { UiState(contacts = it.contacts, error = "The server no longer knows this account. Please register again.") }
            }
        }
    }

    /** The server dedupes by message id, so resending something it already has is harmless. */
    private fun resendPending() {
        for ((peer, messages) in state.value.messages) {
            for (m in messages) if (m.status == MessageStatus.SENDING) client.send(ClientFrame.Send(m.id, peer, m.body))
        }
    }

    private fun handle(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Message -> {
                if (state.value.contacts.none { it.id == frame.from }) saveContact(Contact(frame.from, frame.fromName))
                val known = state.value.messages[frame.from].orEmpty().any { it.id == frame.id }
                if (!known) addMessage(frame.from, ChatMessage(frame.id, fromMe = false, frame.body, frame.sentAt, MessageStatus.RECEIVED))
                // Ack even duplicates: it means the server's earlier copy wasn't deleted yet.
                client.send(ClientFrame.Ack(frame.id))
            }
            is ServerFrame.Accepted -> setStatus(frame.id, MessageStatus.SENT)
            is ServerFrame.Error -> {
                val id = frame.id
                if (id != null) setStatus(id, MessageStatus.FAILED)
                else _state.update { it.copy(error = frame.reason) }
            }
        }
    }

    private fun saveContact(contact: Contact) {
        _state.update { it.copy(contacts = it.contacts + contact) }
        prefs.contacts = state.value.contacts
    }

    private fun addMessage(peer: String, message: ChatMessage) = _state.update {
        it.copy(messages = it.messages + (peer to (it.messages[peer].orEmpty() + message)))
    }

    private fun setStatus(id: String, status: MessageStatus) = _state.update { s ->
        s.copy(messages = s.messages.mapValues { (_, list) -> list.map { if (it.id == id) it.copy(status = status) else it } })
    }
}
