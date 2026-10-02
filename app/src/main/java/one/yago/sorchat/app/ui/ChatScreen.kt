package one.yago.sorchat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import one.yago.sorchat.app.ChatMessage
import one.yago.sorchat.app.Contact
import one.yago.sorchat.app.MessageStatus
import one.yago.sorchat.app.Playback
import one.yago.sorchat.app.R
import one.yago.sorchat.app.formatDuration
import one.yago.sorchat.app.isVoice

/** Voice note state and actions for [ChatScreen]. */
class VoiceControls(
    /** [SystemClock.elapsedRealtime] when recording started, or null if not recording. */
    val recordingSince: Long?,
    val playback: Playback?,
    val onStartRecording: () -> Unit,
    val onCancelRecording: () -> Unit,
    val onFinishRecording: () -> Unit,
    val onTogglePlayback: (ChatMessage) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contact: Contact,
    messages: List<ChatMessage>,
    connected: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
    onVisible: (String?) -> Unit,
    voice: VoiceControls,
) {
    LifecycleResumeEffect(contact.id) {
        onVisible(contact.id)
        onPauseOrDispose { onVisible(null) }
    }
    var draft by rememberSaveable(contact.id) { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                title = {
                    Column {
                        Text(contact.name)
                        Text(contact.id, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { ConnectionIndicator(connected, Modifier.padding(end = 16.dp)) },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(8.dp),
            ) {
                error?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissError) { Text("OK") }
                    }
                }
                if (voice.recordingSince != null) {
                    RecordingBar(voice.recordingSince, onCancel = voice.onCancelRecording, onSend = voice.onFinishRecording)
                } else {
                    InputRow(
                        draft = draft,
                        onDraftChange = { draft = it },
                        onSend = {
                            onSend(draft)
                            draft = ""
                        },
                        onRecord = voice.onStartRecording,
                    )
                }
            }
        },
    ) { padding ->
        // Reversed so the list starts at the bottom and stays pinned to the newest message.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages.asReversed(), key = ChatMessage::id) { message ->
                MessageBubble(message, voice.playback?.takeIf { it.messageId == message.id }, voice.onTogglePlayback)
            }
        }
    }
}

@Composable
private fun InputRow(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit, onRecord: () -> Unit) {
    val context = LocalContext.current
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onRecord()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Message") },
            maxLines = 4,
            modifier = Modifier.weight(1f),
        )
        if (draft.isNotBlank()) {
            TextButton(onClick = onSend) { Text("Send") }
        } else {
            IconButton(onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (granted) onRecord() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Record voice message")
            }
        }
    }
}

@Composable
private fun RecordingBar(since: Long, onCancel: () -> Unit, onSend: () -> Unit) {
    val elapsed by produceState(0L, since) {
        while (true) {
            value = SystemClock.elapsedRealtime() - since
            delay(200)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.padding(start = 8.dp).size(10.dp).background(Color(0xFFC62828), CircleShape))
        Text("Recording ${formatDuration(elapsed)}", modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Cancel") }
        TextButton(onClick = onSend) { Text("Send") }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, playback: Playback?, onTogglePlayback: (ChatMessage) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentAlignment = if (message.fromMe) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (message.fromMe) colors.primaryContainer else colors.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.isVoice) VoiceNote(message, playback, onTogglePlayback) else Text(message.body)
                if (message.fromMe) {
                    Text(
                        text = when (message.status) {
                            MessageStatus.SENDING -> "sending…"
                            MessageStatus.SENT -> "sent"
                            MessageStatus.FAILED -> "failed"
                            MessageStatus.RECEIVED -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.status == MessageStatus.FAILED) colors.error else colors.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceNote(message: ChatMessage, playback: Playback?, onToggle: (ChatMessage) -> Unit) {
    if (message.localPath == null) {
        Text("Voice message unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val duration = message.durationMs ?: 0
    val position = playback?.positionMs ?: 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onToggle(message) }) {
            Icon(
                painterResource(if (playback?.playing == true) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (playback?.playing == true) "Pause" else "Play",
            )
        }
        Column(Modifier.width(140.dp)) {
            LinearProgressIndicator(
                progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                formatDuration(if (playback != null) position else duration),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun ConnectionIndicator(connected: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(if (connected) Color(0xFF2E7D32) else Color(0xFFC62828), CircleShape))
        Text(if (connected) "online" else "offline", style = MaterialTheme.typography.labelMedium)
    }
}
