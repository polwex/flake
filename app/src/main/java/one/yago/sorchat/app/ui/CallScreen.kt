package one.yago.sorchat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import one.yago.sorchat.app.Call
import one.yago.sorchat.app.CallPhase
import one.yago.sorchat.app.R
import one.yago.sorchat.app.formatDuration
import one.yago.sorchat.protocol.EndReason
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

/** What the call screen needs to show video. */
class CallVideo(val eglContext: EglBase.Context, val local: VideoTrack?, val remote: VideoTrack?)

@Composable
fun CallScreen(
    call: Call,
    video: CallVideo,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onCameraOn: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
) {
    // Leaving the screen mid-call would strand the user without controls; hang up or decline instead.
    BackHandler {}
    val accept = withCallPermissions(call.video, onAccept)
    val showVideo = call.video && call.phase != CallPhase.INCOMING && call.phase != CallPhase.ENDED

    Surface(Modifier.fillMaxSize(), color = if (showVideo) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(Modifier.fillMaxSize()) {
            if (showVideo) {
                video.remote?.let { VideoView(it, video.eglContext, mirror = false, Modifier.fillMaxSize()) }
                video.local?.takeIf { call.cameraOn }?.let {
                    VideoView(
                        it, video.eglContext, mirror = true,
                        modifier = Modifier
                            .safeDrawingPadding()
                            .padding(16.dp)
                            .size(width = 110.dp, height = 160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .align(Alignment.TopEnd),
                        onTop = true,
                    )
                }
            }
            val textColor = if (showVideo) Color.White else MaterialTheme.colorScheme.onSurface
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (showVideo) 16.dp else 96.dp))
                Text(call.peer.name, style = MaterialTheme.typography.headlineLarge, color = textColor)
                Spacer(Modifier.height(8.dp))
                Text(statusText(call), style = MaterialTheme.typography.titleMedium, color = textColor.copy(alpha = 0.8f))
                Spacer(Modifier.weight(1f))

                when (call.phase) {
                    CallPhase.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(R.drawable.ic_call_end, "Decline", Red, onDecline)
                        RoundButton(if (call.video) R.drawable.ic_videocam else R.drawable.ic_call, "Accept", Green, accept)
                    }
                    CallPhase.ENDED -> Unit
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Toggle(if (call.muted) "Unmute" else "Mute", call.muted) { onMute(!call.muted) }
                            Toggle("Speaker", call.speaker) { onSpeaker(!call.speaker) }
                        }
                        if (call.video && video.local != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Toggle(if (call.cameraOn) "Camera off" else "Camera on", !call.cameraOn) { onCameraOn(!call.cameraOn) }
                                if (call.cameraOn) Toggle("Flip", false, onSwitchCamera)
                            }
                        }
                        RoundButton(R.drawable.ic_call_end, "Hang up", Red, onHangUp, labelColor = textColor)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** Renders a WebRTC video track. [onTop] for a view overlapping another video (the self-view). */
@Composable
private fun VideoView(track: VideoTrack, eglContext: EglBase.Context, mirror: Boolean, modifier: Modifier = Modifier, onTop: Boolean = false) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglContext, null)
                setMirror(mirror)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (onTop) setZOrderMediaOverlay(true)
                renderer = this
            }
        },
        onRelease = { it.release() },
        modifier = modifier.background(Color.Black),
    )
    DisposableEffect(track, renderer) {
        val sink = renderer
        sink?.let(track::addSink)
        onDispose { sink?.let { runCatching { track.removeSink(it) } } }
    }
}

@Composable
private fun statusText(call: Call): String = when (call.phase) {
    CallPhase.OUTGOING -> "Calling…"
    CallPhase.INCOMING -> if (call.video) "Incoming video call" else "Incoming call"
    CallPhase.CONNECTING -> "Connecting…"
    CallPhase.ACTIVE -> {
        val since = call.connectedAt ?: 0
        val elapsed by produceState(0L, since) {
            while (true) {
                value = System.currentTimeMillis() - since
                delay(500)
            }
        }
        formatDuration(elapsed)
    }
    CallPhase.ENDED -> when {
        call.noAnswer -> "No answer"
        else -> when (call.endReason) {
            EndReason.DECLINED -> "Declined"
            EndReason.BUSY -> "Busy"
            EndReason.UNAVAILABLE -> "Not available"
            EndReason.FAILED -> "Call failed"
            EndReason.HANGUP, null -> "Call ended"
        }
    }
}

@Composable
private fun RoundButton(icon: Int, label: String, color: Color, onClick: () -> Unit, labelColor: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
            modifier = Modifier.size(72.dp),
        ) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(32.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        colors = if (on) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
    ) { Text(label) }
}
