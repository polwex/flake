package one.yago.sorchat.app

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack

data class UiState(
    val me: Identity? = null,
    val contacts: List<Contact> = emptyList(),
    /** Newest message per contact id. */
    val lastMessages: Map<String, ChatMessage> = emptyMap(),
    val openChat: String? = null,
    /** Messages of [openChat], oldest first. */
    val conversation: List<ChatMessage> = emptyList(),
    val connected: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** [SystemClock.elapsedRealtime] when the current voice recording started, if one is running. */
    val recordingSince: Long? = null,
)

/** State owned by the ViewModel itself; everything else comes from [ChatRepository]. */
private data class LocalState(
    val openChat: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val recordingSince: Long? = null,
)

private data class Session(val me: Identity?, val connected: Boolean, val notice: String?)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ChatRepository.get(app)
    private val local = MutableStateFlow(LocalState())
    private val recorder = VoiceRecorder(app)
    private val player = VoicePlayer(viewModelScope)
    val playback: StateFlow<Playback?> = player.state
    val call: StateFlow<Call?> = repo.calls.call
    val localVideo: StateFlow<VideoTrack?> = repo.calls.localVideo
    val remoteVideo: StateFlow<VideoTrack?> = repo.calls.remoteVideo
    val eglContext: EglBase.Context get() = repo.calls.eglBase.eglBaseContext

    @OptIn(ExperimentalCoroutinesApi::class)
    private val conversation = local.map { it.openChat }.distinctUntilChanged()
        .flatMapLatest { peer -> if (peer == null) flowOf(emptyList()) else repo.dao.conversation(peer) }

    private val session = combine(repo.identity, repo.connected, repo.notice, ::Session)

    val state: StateFlow<UiState> = combine(
        local, session, repo.dao.contacts(), repo.dao.lastMessages(), conversation,
    ) { local, session, contacts, lastMessages, conversation ->
        UiState(
            me = session.me,
            contacts = contacts,
            lastMessages = lastMessages.associateBy { it.peer },
            openChat = local.openChat,
            conversation = conversation,
            connected = session.connected,
            busy = local.busy,
            error = local.error ?: session.notice,
            recordingSince = local.recordingSince,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState(me = repo.identity.value))

    fun register(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        runBusy("Couldn't register") { repo.register(trimmed) }
    }

    fun addContact(rawId: String) {
        val me = repo.identity.value ?: return
        val id = rawId.trim().lowercase()
        if (id.isEmpty() || id == me.id || state.value.contacts.any { it.id == id }) return
        runBusy("Couldn't add contact") {
            if (!repo.addContact(id)) local.update { it.copy(error = "No user with ID \"$id\"") }
        }
    }

    fun openChat(contactId: String?) {
        cancelRecording()
        player.stop()
        local.update { it.copy(openChat = contactId) }
    }

    /** Called while a chat is on screen, so its messages don't also raise notifications. */
    fun setVisibleChat(contactId: String?) {
        repo.visibleChat = contactId
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
        repo.notice.value = null
    }

    fun send(text: String) {
        val peer = local.value.openChat ?: return
        val body = text.trim().ifEmpty { return }
        viewModelScope.launch { repo.send(peer, body) }
    }

    fun startRecording() {
        try {
            recorder.start(repo.newRecordingFile())
            local.update { it.copy(recordingSince = SystemClock.elapsedRealtime()) }
        } catch (e: Exception) {
            local.update { it.copy(error = "Couldn't start recording: ${e.message}") }
        }
    }

    fun cancelRecording() {
        recorder.cancel()
        local.update { it.copy(recordingSince = null) }
    }

    fun finishRecording() {
        val peer = local.value.openChat
        val recording = recorder.stop()
        local.update { it.copy(recordingSince = null) }
        if (peer == null || recording == null) return
        val (file, durationMs) = recording
        viewModelScope.launch { repo.sendVoice(peer, file, durationMs) }
    }

    fun startCall(video: Boolean) {
        val peer = state.value.contacts.firstOrNull { it.id == local.value.openChat } ?: return
        player.stop()
        repo.calls.start(peer, video)
    }

    fun acceptCall() = repo.calls.accept()
    fun declineCall() = repo.calls.decline()
    fun hangUp() = repo.calls.hangUp()
    fun setMuted(muted: Boolean) = repo.calls.setMuted(muted)
    fun setSpeaker(on: Boolean) = repo.calls.setSpeaker(on)
    fun setCameraOn(on: Boolean) = repo.calls.setCameraOn(on)
    fun switchCamera() = repo.calls.switchCamera()

    fun togglePlayback(message: ChatMessage) {
        try {
            player.toggle(message)
        } catch (e: Exception) {
            player.stop()
            local.update { it.copy(error = "Couldn't play voice message: ${e.message}") }
        }
    }

    override fun onCleared() {
        recorder.cancel()
        player.stop()
    }

    private fun runBusy(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            local.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: Exception) {
                local.update { it.copy(error = "$failure: ${e.message}") }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }
}
