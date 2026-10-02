package one.yago.sorchat.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
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
 * Calls are registered with Android's Telecom framework (core-telecom), which lets them ring
 * and run from the background, handles audio routing, and integrates with headsets and
 * cellular calls. All state changes run on one sequential dispatcher, so WebRTC and Telecom
 * callbacks (which arrive on their own threads) and user actions never race each other.
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
    private val notifications = Notifications(context)

    private val _call = MutableStateFlow<Call?>(null)
    val call: StateFlow<Call?> = _call.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    /** The caller's offer. For a call announced by push, it arrives once the WebSocket connects. */
    private var offer = CompletableDeferred<String>()
    /** Candidates that arrived before the remote description was set. */
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var ringtone: Ringtone? = null
    private var timeout: Job? = null

    /** Null if this device doesn't support Telecom; calls then work only while the app is open. */
    private val telecom: CallsManager? = try {
        CallsManager(context).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
    } catch (e: Exception) {
        Log.w(TAG, "Telecom unavailable", e)
        null
    }

    @Volatile
    private var control: CallControlScope? = null
    private var endpoints: List<CallEndpointCompat> = emptyList()

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
        resetConnectionState()
        val call = Call(UUID.randomUUID().toString(), peer, CallPhase.OUTGOING)
        _call.value = call
        register(call, incoming = false)
        try {
            val pc = createPeerConnection(call)
            val sdp = pc.awaitCreate(offer = true)
            pc.awaitSetLocal(sdp)
            send(CallSignal.Invite(sdp.description))
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

    /** A push announced an incoming call: start ringing right away; the offer follows over the WebSocket. */
    fun onIncomingPush(callId: String, from: String, fromName: String) = scope.launch {
        if (inCall) return@launch // busy: answered as such when the invite itself arrives
        ring(Call(callId, Contact(from, fromName), CallPhase.INCOMING))
    }

    /** A push said the caller gave up before we connected. */
    fun onCancelledPush(callId: String) = scope.launch {
        val call = _call.value ?: return@launch
        if (call.id == callId && call.phase == CallPhase.INCOMING) finish(EndReason.HANGUP)
    }

    fun accept() = scope.launch {
        val call = _call.value?.takeIf { it.phase == CallPhase.INCOMING } ?: return@launch
        stopRinging()
        _call.value = call.copy(phase = CallPhase.CONNECTING)
        control?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
        CallService.update(context, call.peer.name, CallService.Mode.ONGOING, telecom = control != null)
        try {
            val sdp = withTimeoutOrNull(OFFER_TIMEOUT) { offer.await() } ?: error("The call details never arrived")
            val pc = createPeerConnection(call)
            pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
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
        val control = control
        if (control != null) {
            // Off means back to the most private route available: headset, then earpiece.
            val target = if (on) {
                endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
            } else {
                endpoints.filter { it.type != CallEndpointCompat.TYPE_SPEAKER }
                    .minByOrNull { PRIVATE_ROUTES.indexOf(it.type).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
            }
            target?.let { control.requestEndpointChange(it) }
            return@launch // the new route is reported through currentCallEndpoint
        }
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
                if (current != null && current.id == frame.callId) {
                    // Already ringing because of the push: this brings the offer.
                    offer.complete(signal.sdp)
                    return@launch
                }
                if (inCall) {
                    sendSignal(frame.from, frame.callId, CallSignal.End(EndReason.BUSY))
                    return@launch
                }
                ring(Call(frame.callId, Contact(frame.from, frame.fromName), CallPhase.INCOMING))
                offer.complete(signal.sdp)
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
                        Log.d(TAG, "Remote candidate: ${describeCandidate(signal.candidate)}")
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

    private fun ring(call: Call) {
        resetConnectionState()
        _call.value = call
        register(call, incoming = true)
        startRinging()
        // If the caller's hang-up never reaches us (e.g. its push is lost), stop eventually.
        timeout = scope.launch {
            delay(RING_TIMEOUT + 15.seconds)
            if (_call.value?.let { it.id == call.id && it.phase == CallPhase.INCOMING } == true) finish(EndReason.HANGUP)
        }
    }

    /**
     * Registers the call with Telecom, then starts [CallService]. Telecom calls may run a
     * `phoneCall` foreground service from the background, which is what lets a pushed call ring.
     */
    private fun register(call: Call, incoming: Boolean) {
        val telecom = telecom
        val mode = if (incoming) CallService.Mode.INCOMING else CallService.Mode.ONGOING
        if (telecom == null) {
            CallService.update(context, call.peer.name, mode, telecom = false)
            return
        }
        scope.launch {
            try {
                telecom.addCall(
                    CallAttributesCompat(
                        displayName = call.peer.name,
                        address = Uri.fromParts("sorchat", call.peer.id, null),
                        direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
                        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    ),
                    // Answered or ended from outside the app: a headset button, a watch, a cellular call.
                    onAnswer = { accept() },
                    onDisconnect = { if (_call.value?.id == call.id) { if (_call.value?.phase == CallPhase.INCOMING) decline() else hangUp() } },
                    onSetActive = {},
                    onSetInactive = {},
                ) {
                    scope.launch {
                        if (_call.value?.id != call.id || !inCall) {
                            // Ended while Telecom was still setting up.
                            launch { disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                            return@launch
                        }
                        control = this@addCall
                        CallService.update(context, call.peer.name, mode, telecom = true)
                    }
                    launch { availableEndpoints.collect { endpoints = it } }
                    launch {
                        currentCallEndpoint.collect { endpoint ->
                            _call.update { it?.copy(speaker = endpoint.type == CallEndpointCompat.TYPE_SPEAKER) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Telecom didn't accept the call", e)
                if (_call.value?.id == call.id && inCall) CallService.update(context, call.peer.name, mode, telecom = false)
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
        // With Telecom, the system sets up call audio itself.
        if (telecom == null) audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
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
        if (telecom == null) {
            audioManager.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        }
        control?.let { c ->
            val cause = when {
                call.phase == CallPhase.INCOMING && reason == EndReason.DECLINED -> DisconnectCause.REJECTED
                call.phase == CallPhase.INCOMING -> DisconnectCause.MISSED
                reason == EndReason.BUSY -> DisconnectCause.BUSY
                reason == EndReason.FAILED || reason == EndReason.UNAVAILABLE -> DisconnectCause.ERROR
                else -> DisconnectCause.LOCAL
            }
            c.launch { c.disconnect(DisconnectCause(cause)) }
        }
        control = null
        CallService.stop(context)
        if (call.phase == CallPhase.INCOMING && reason != EndReason.DECLINED) notifications.showMissedCall(call.peer)
        _call.value = call.copy(phase = CallPhase.ENDED, endReason = reason, noAnswer = noAnswer)
        // Show the outcome briefly, then go back to idle unless a new call started meanwhile.
        scope.launch {
            delay(2.seconds)
            if (_call.value?.id == call.id) _call.value = null
        }
    }

    /** Logs which candidate pair WebRTC settled on: direct (host/srflx) or through the relay. */
    private fun logSelectedPair() {
        peerConnection?.getStats { report ->
            val stats = report.statsMap
            val pair = stats.values.firstOrNull {
                it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded"
            } ?: return@getStats
            fun side(id: Any?) = stats[id]?.members?.let { "${it["candidateType"]}/${it["protocol"]} ${it["address"]}:${it["port"]}" }
            Log.d(TAG, "Selected pair: local ${side(pair.members["localCandidateId"])} ⇄ remote ${side(pair.members["remoteCandidateId"])}")
        }
    }

    private fun resetConnectionState() {
        peerConnection = null
        audioTrack = null
        offer = CompletableDeferred()
        pendingIce.clear()
        remoteDescriptionSet = false
        endpoints = emptyList()
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
            Log.d(TAG, "Local candidate: ${describeCandidate(candidate.sdp)}")
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
                            control?.let { c -> c.launch { c.setActive() } }
                            logSelectedPair()
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
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE connection: $state")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d(TAG, "ICE gathering: $state")
        }
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
        val OFFER_TIMEOUT = 15.seconds
        /** Audio routes when the speaker is off, most preferred first. */
        val PRIVATE_ROUTES = listOf(
            CallEndpointCompat.TYPE_BLUETOOTH,
            CallEndpointCompat.TYPE_WIRED_HEADSET,
            CallEndpointCompat.TYPE_EARPIECE,
        )
    }
}

/** "relay udp 38.60.254.66:49201" from an SDP candidate line. */
private fun describeCandidate(sdp: String): String {
    val parts = sdp.split(" ")
    val type = parts.getOrNull(parts.indexOf("typ") + 1) ?: "?"
    return "$type ${parts.getOrNull(2)?.lowercase()} ${parts.getOrNull(4)}:${parts.getOrNull(5)}"
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
