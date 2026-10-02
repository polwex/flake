package one.yago.sorchat.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import one.yago.sorchat.protocol.CallSignal
import one.yago.sorchat.protocol.EndReason
import one.yago.sorchat.protocol.IceServer
import one.yago.sorchat.protocol.ServerFrame
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

enum class CallPhase { OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED }

data class Call(
    val id: String,
    val peer: Contact,
    val phase: CallPhase,
    /** When media started flowing, for the call timer. */
    val connectedAt: Long? = null,
    val endReason: EndReason? = null,
    /** Ended locally because nobody picked up. */
    val noAnswer: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
)

/**
 * 1:1 audio calls over WebRTC. Signaling goes through the chat WebSocket via [sendSignal].
 * All state changes run on one sequential dispatcher, so WebRTC callbacks (which arrive on
 * WebRTC's own threads) and user actions never race each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManager(
    private val context: Context,
    parentScope: CoroutineScope,
    private val sendSignal: (to: String, callId: String, signal: CallSignal) -> Boolean,
    private val iceServers: suspend () -> List<IceServer>,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.Default.limitedParallelism(1))
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _call = MutableStateFlow<Call?>(null)
    val call: StateFlow<Call?> = _call.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var pendingOffer: String? = null
    /** Candidates that arrived before the remote description was set. */
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var ringtone: Ringtone? = null
    private var timeout: Job? = null

    init {
        scope.launch { _call.collect { Log.d(TAG, "Call state: ${it?.phase} ${it?.endReason ?: ""}") } }
    }

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val audioDevice = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder().setAudioDeviceModule(audioDevice).createPeerConnectionFactory()
    }

    /** Whether a call is in progress (anything but idle or the brief "ended" display). */
    val inCall: Boolean get() = _call.value.let { it != null && it.phase != CallPhase.ENDED }

    fun start(peer: Contact) = scope.launch {
        if (inCall) return@launch
        val call = Call(UUID.randomUUID().toString(), peer, CallPhase.OUTGOING)
        _call.value = call
        CallService.start(context, peer.name)
        try {
            val pc = createPeerConnection(call)
            val offer = pc.awaitCreate(offer = true)
            pc.awaitSetLocal(offer)
            send(CallSignal.Invite(offer.description))
            timeout = scope.launch {
                delay(RING_TIMEOUT)
                if (_call.value?.let { it.id == call.id && it.phase == CallPhase.OUTGOING } == true) {
                    send(CallSignal.End(EndReason.HANGUP))
                    finish(EndReason.HANGUP, noAnswer = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start call", e)
            send(CallSignal.End(EndReason.FAILED))
            finish(EndReason.FAILED)
        }
    }

    fun accept() = scope.launch {
        val call = _call.value?.takeIf { it.phase == CallPhase.INCOMING } ?: return@launch
        val offer = pendingOffer ?: return@launch
        stopRinging()
        _call.value = call.copy(phase = CallPhase.CONNECTING)
        CallService.start(context, call.peer.name)
        try {
            val pc = createPeerConnection(call)
            pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, offer))
            onRemoteDescriptionSet(pc)
            val answer = pc.awaitCreate(offer = false)
            pc.awaitSetLocal(answer)
            send(CallSignal.Accept(answer.description))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't accept call", e)
            send(CallSignal.End(EndReason.FAILED))
            finish(EndReason.FAILED)
        }
    }

    fun decline() = scope.launch {
        if (_call.value?.phase != CallPhase.INCOMING) return@launch
        send(CallSignal.End(EndReason.DECLINED))
        finish(EndReason.DECLINED)
    }

    fun hangUp() = scope.launch {
        if (!inCall) return@launch
        send(CallSignal.End(EndReason.HANGUP))
        finish(EndReason.HANGUP)
    }

    fun setMuted(muted: Boolean) = scope.launch {
        audioTrack?.setEnabled(!muted)
        _call.update { it?.copy(muted = muted) }
    }

    fun setSpeaker(on: Boolean) = scope.launch {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let(audioManager::setCommunicationDevice)
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
        _call.update { it?.copy(speaker = on) }
    }

    /** A call frame from the server. */
    fun onSignal(frame: ServerFrame.Call) = scope.launch {
        val current = _call.value
        when (val signal = frame.signal) {
            is CallSignal.Invite -> {
                if (inCall) {
                    if (current?.id != frame.callId) sendSignal(frame.from, frame.callId, CallSignal.End(EndReason.BUSY))
                    return@launch
                }
                resetConnectionState()
                pendingOffer = signal.sdp
                _call.value = Call(frame.callId, Contact(frame.from, frame.fromName), CallPhase.INCOMING)
                startRinging()
            }
            else -> {
                if (current == null || current.id != frame.callId || current.phase == CallPhase.ENDED) return@launch
                when (signal) {
                    is CallSignal.Accept -> {
                        val pc = peerConnection ?: return@launch
                        timeout?.cancel()
                        _call.value = current.copy(phase = CallPhase.CONNECTING)
                        pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, signal.sdp))
                        onRemoteDescriptionSet(pc)
                    }
                    is CallSignal.Ice -> {
                        val candidate = IceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.candidate)
                        val pc = peerConnection
                        if (pc != null && remoteDescriptionSet) pc.addIceCandidate(candidate) else pendingIce += candidate
                    }
                    is CallSignal.End -> finish(signal.reason)
                    is CallSignal.Invite -> Unit // handled above
                }
            }
        }
    }

    private suspend fun createPeerConnection(call: Call): PeerConnection {
        val servers = iceServers().map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .apply {
                    server.username?.let(::setUsername)
                    server.credential?.let(::setPassword)
                }
                .createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = checkNotNull(factory.createPeerConnection(config, Observer(call.id))) { "Couldn't create PeerConnection" }
        val track = factory.createAudioTrack("audio0", factory.createAudioSource(MediaConstraints()))
        pc.addTrack(track, listOf("call"))
        peerConnection = pc
        audioTrack = track
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        return pc
    }

    private fun onRemoteDescriptionSet(pc: PeerConnection) {
        remoteDescriptionSet = true
        pendingIce.forEach(pc::addIceCandidate)
        pendingIce.clear()
    }

    private fun send(signal: CallSignal) {
        val call = _call.value ?: return
        sendSignal(call.peer.id, call.id, signal)
    }

    private fun finish(reason: EndReason, noAnswer: Boolean = false) {
        val call = _call.value ?: return
        timeout?.cancel()
        stopRinging()
        peerConnection?.dispose()
        resetConnectionState()
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        CallService.stop(context)
        _call.value = call.copy(phase = CallPhase.ENDED, endReason = reason, noAnswer = noAnswer)
        // Show the outcome briefly, then go back to idle unless a new call started meanwhile.
        scope.launch {
            delay(2.seconds)
            if (_call.value?.id == call.id) _call.value = null
        }
    }

    private fun resetConnectionState() {
        peerConnection = null
        audioTrack = null
        pendingOffer = null
        pendingIce.clear()
        remoteDescriptionSet = false
    }

    private fun startRinging() {
        ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ?.apply { isLooping = true; play() }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
    }

    private inner class Observer(private val callId: String) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch {
                if (_call.value?.id == callId) send(CallSignal.Ice(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            scope.launch {
                val call = _call.value?.takeIf { it.id == callId } ?: return@launch
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED ->
                        if (call.phase != CallPhase.ACTIVE) {
                            _call.value = call.copy(phase = CallPhase.ACTIVE, connectedAt = System.currentTimeMillis())
                        }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        send(CallSignal.End(EndReason.FAILED))
                        finish(EndReason.FAILED)
                    }
                    else -> Unit
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private companion object {
        const val TAG = "CallManager"
        val RING_TIMEOUT = 45.seconds
    }
}

private suspend fun PeerConnection.awaitCreate(offer: Boolean): SessionDescription =
    suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String) = cont.resumeWithException(IllegalStateException(error))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }
        if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
    }

private suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription) = awaitSet { setLocalDescription(it, sdp) }

private suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription) = awaitSet { setRemoteDescription(it, sdp) }

private suspend fun awaitSet(set: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
    set(object : SdpObserver {
        override fun onSetSuccess() = cont.resume(Unit)
        override fun onSetFailure(error: String) = cont.resumeWithException(IllegalStateException(error))
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onCreateFailure(error: String) = Unit
    })
}
