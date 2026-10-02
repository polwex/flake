package one.yago.sorchat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import one.yago.sorchat.app.Call
import one.yago.sorchat.app.CallPhase
import one.yago.sorchat.app.formatDuration
import one.yago.sorchat.app.ui.theme.Sunset
import one.yago.sorchat.protocol.EndReason
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.cos
import kotlin.math.sin

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
    val showVideo = call.video && video.remote != null && call.phase == CallPhase.ACTIVE

    Box(Modifier.fillMaxSize()) {
        DriftingSunset(Modifier.fillMaxSize())
        if (call.video && call.phase != CallPhase.INCOMING && call.phase != CallPhase.ENDED) {
            video.remote?.let {
                VideoView(it, video.eglContext, mirror = false, Modifier.fillMaxSize())
                // Fades at the top and bottom keep the name and controls readable on bright video.
                Box(
                    Modifier.fillMaxWidth().height(180.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
                )
                Box(
                    Modifier.fillMaxWidth().height(320.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
                )
            }
            video.local?.takeIf { call.cameraOn }?.let {
                VideoView(
                    it, video.eglContext, mirror = true,
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(16.dp)
                        .size(width = 110.dp, height = 160.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .align(Alignment.TopEnd),
                    onTop = true,
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showVideo) {
                // Over video: just a compact name and timer, out of the way.
                Text(call.peer.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(statusText(call), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
            } else {
                Spacer(Modifier.height(72.dp))
                PulsingAvatar(call)
                Spacer(Modifier.height(24.dp))
                Text(call.peer.name, style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(statusText(call), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
            }
            Spacer(Modifier.weight(1f))

            when (call.phase) {
                CallPhase.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    LabeledButton("Decline") {
                        RoundIconButton(Icons.Rounded.CallEnd, "Decline", onDecline, size = 76.dp, background = solid(Sunset.Red))
                    }
                    LabeledButton("Accept") { BouncingAccept(if (call.video) Icons.Rounded.Videocam else Icons.Rounded.Call, accept) }
                }
                CallPhase.ENDED -> Unit
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Toggle(if (call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (call.muted) "Unmute" else "Mute", call.muted) { onMute(!call.muted) }
                        Toggle(Icons.AutoMirrored.Rounded.VolumeUp, "Speaker", call.speaker) { onSpeaker(!call.speaker) }
                        if (call.video && video.local != null) {
                            Toggle(if (call.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, if (call.cameraOn) "Camera off" else "Camera on", !call.cameraOn) {
                                onCameraOn(!call.cameraOn)
                            }
                            if (call.cameraOn) Toggle(Icons.Rounded.Cameraswitch, "Switch camera", false, onSwitchCamera)
                        }
                    }
                    RoundIconButton(Icons.Rounded.CallEnd, "Hang up", onHangUp, size = 76.dp, background = solid(Sunset.Red))
                }
            }
        }
    }
}

/** The background: a deep sunset whose light slowly wanders around. */
@Composable
private fun DriftingSunset(modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "drift").animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing)),
        label = "t",
    )
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF3A1640), Color(0xFF6B2F6B), Color(0xFFB83A6E))))
        val glow = Offset(size.width * (0.5f + 0.3f * cos(t)), size.height * (0.35f + 0.15f * sin(t * 2)))
        drawCircle(Brush.radialGradient(listOf(Sunset.Coral.copy(alpha = 0.65f), Color.Transparent), center = glow, radius = size.width), radius = size.width, center = glow)
        val glow2 = Offset(size.width * (0.5f - 0.35f * sin(t)), size.height * (0.8f + 0.1f * cos(t)))
        drawCircle(Brush.radialGradient(listOf(Sunset.Orange.copy(alpha = 0.5f), Color.Transparent), center = glow2, radius = size.width * 0.8f), radius = size.width * 0.8f, center = glow2)
    }
}

/** The caller's avatar; rings ripple outwards while the call isn't connected yet. */
@Composable
private fun PulsingAvatar(call: Call) {
    val waiting = call.phase == CallPhase.OUTGOING || call.phase == CallPhase.INCOMING || call.phase == CallPhase.CONNECTING
    val ripple by rememberInfiniteTransition(label = "ripple").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "ripple",
    )
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        if (waiting) {
            Canvas(Modifier.fillMaxSize()) {
                for (k in 0..1) {
                    val p = (ripple + k * 0.5f) % 1f
                    drawCircle(Color.White.copy(alpha = 0.35f * (1 - p)), radius = size.minDimension / 2 * (0.55f + 0.45f * p))
                }
            }
        }
        Avatar(call.peer.name, call.peer.id, size = 120.dp)
    }
}

/** The accept button bounces to draw the eye. */
@Composable
private fun BouncingAccept(icon: androidx.compose.ui.graphics.vector.ImageVector, onAccept: () -> Unit) {
    val bounce by rememberInfiniteTransition(label = "bounce").animateFloat(
        initialValue = 0f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "bounce",
    )
    RoundIconButton(icon, "Accept", onAccept, size = 76.dp, background = solid(Sunset.Green), modifier = Modifier.offset { IntOffset(0, bounce.dp.roundToPx()) })
}

@Composable
private fun LabeledButton(label: String, button: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        button()
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(top = 8.dp))
    }
}

/** A round control that turns solid white while active. */
@Composable
private fun Toggle(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    RoundIconButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        size = 60.dp,
        background = solid(if (active) Color.White else Color.White.copy(alpha = 0.2f)),
        contentColor = if (active) Sunset.Plum else Color.White,
    )
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
