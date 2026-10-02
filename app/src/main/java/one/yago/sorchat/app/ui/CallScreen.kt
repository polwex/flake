package one.yago.sorchat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import one.yago.sorchat.app.Call
import one.yago.sorchat.app.CallPhase
import one.yago.sorchat.app.R
import one.yago.sorchat.app.formatDuration
import one.yago.sorchat.protocol.EndReason

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

@Composable
fun CallScreen(
    call: Call,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
) {
    // Leaving the screen mid-call would strand the user without controls; hang up or decline instead.
    BackHandler {}
    val accept = withMicPermission(onAccept)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(96.dp))
            Text(call.peer.name, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(statusText(call), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))

            when (call.phase) {
                CallPhase.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(R.drawable.ic_call_end, "Decline", Red, onDecline)
                    RoundButton(R.drawable.ic_call, "Accept", Green, accept)
                }
                CallPhase.ENDED -> Unit
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Toggle(if (call.muted) "Unmute" else "Mute", call.muted) { onMute(!call.muted) }
                        Toggle("Speaker", call.speaker) { onSpeaker(!call.speaker) }
                    }
                    RoundButton(R.drawable.ic_call_end, "Hang up", Red, onHangUp)
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun statusText(call: Call): String = when (call.phase) {
    CallPhase.OUTGOING -> "Calling…"
    CallPhase.INCOMING -> "Incoming call"
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
private fun RoundButton(icon: Int, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
            modifier = Modifier.size(72.dp),
        ) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(32.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
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
