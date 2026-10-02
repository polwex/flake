package one.yago.sorchat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import one.yago.sorchat.app.ChatMessage
import one.yago.sorchat.app.Contact
import one.yago.sorchat.app.MessageStatus
import one.yago.sorchat.app.Playback
import one.yago.sorchat.app.formatDuration
import one.yago.sorchat.app.isFile
import one.yago.sorchat.app.isImage
import one.yago.sorchat.app.isVoice
import one.yago.sorchat.app.ui.theme.LocalGradients
import one.yago.sorchat.app.ui.theme.Sunset
import java.time.LocalDate
import kotlin.math.sin
import kotlin.random.Random

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

/** A row in the message list: a message or a day separator. */
private sealed interface ChatItem {
    val key: String

    data class Day(val day: LocalDate) : ChatItem {
        override val key = "day-$day"
    }

    data class Message(val message: ChatMessage) : ChatItem {
        override val key = message.id
    }
}

@Composable
fun ChatScreen(
    contact: Contact,
    messages: List<ChatMessage>,
    connected: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onSend: (String) -> Unit,
    onCall: (video: Boolean) -> Unit,
    onBack: () -> Unit,
    onVisible: (String?) -> Unit,
    voice: VoiceControls,
    attachments: AttachmentControls,
) {
    LifecycleResumeEffect(contact.id) {
        onVisible(contact.id)
        onPauseOrDispose { onVisible(null) }
    }
    var draft by rememberSaveable(contact.id) { mutableStateOf("") }
    var attaching by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    // Newest first, because the list is reversed (it starts at the bottom and stays pinned there).
    val items = remember(messages) {
        buildList<ChatItem> {
            var day: LocalDate? = null
            for (m in messages) {
                val d = dayOf(m.sentAt)
                if (d != day) add(ChatItem.Day(d))
                day = d
                add(ChatItem.Message(m))
            }
        }.asReversed()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatTopBar(contact, connected, onBack, onCall)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items, key = ChatItem::key) { item ->
                when (item) {
                    is ChatItem.Day -> DayChip(item.day, Modifier.animateItem())
                    is ChatItem.Message -> MessageBubble(
                        item.message,
                        voice.playback?.takeIf { it.messageId == item.message.id },
                        voice.onTogglePlayback,
                        progress = attachments.transfers[item.message.id],
                        onOpenPhoto = { viewing = item.message.localPath },
                        onDownload = { attachments.onDownload(item.message) },
                        modifier = Modifier.animateItem(placementSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)),
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissError) { Text("OK") }
                    }
                }
            }
            AnimatedContent(
                targetState = voice.recordingSince,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.95f)) togetherWith fadeOut() },
                label = "input",
            ) { recordingSince ->
                if (recordingSince != null) {
                    RecordingBar(recordingSince, onCancel = voice.onCancelRecording, onSend = voice.onFinishRecording)
                } else {
                    InputBar(
                        draft = draft,
                        onDraftChange = { draft = it },
                        onSend = {
                            onSend(draft)
                            draft = ""
                        },
                        onRecord = voice.onStartRecording,
                        onAttach = { attaching = true },
                    )
                }
            }
        }
    }

    if (attaching) AttachSheet(onDismiss = { attaching = false }, onPhoto = attachments.onSendPhoto, onFile = attachments.onSendFile)
    viewing?.let { ImageViewer(it, onClose = { viewing = null }) }
}

/** Attachment state and actions for [ChatScreen]. */
class AttachmentControls(
    /** Upload/download progress by message id. */
    val transfers: Map<String, Float>,
    val onSendPhoto: (android.net.Uri) -> Unit,
    val onSendFile: (android.net.Uri) -> Unit,
    val onDownload: (ChatMessage) -> Unit,
)

@Composable
private fun ChatTopBar(contact: Contact, connected: Boolean, onBack: () -> Unit, onCall: (video: Boolean) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(LocalGradients.current.sunset)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Avatar(contact.name, contact.id, size = 42.dp, ring = Color.White)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(contact.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (connected) contact.id else "connecting…",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            val audioCall = withMicPermission { onCall(false) }
            val videoCall = withCallPermissions(video = true) { onCall(true) }
            RoundIconButton(Icons.Rounded.Call, "Call", audioCall, size = 44.dp)
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.Rounded.Videocam, "Video call", videoCall, size = 44.dp)
        }
    }
}

@Composable
private fun DayChip(day: LocalDate, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                formatDayLabel(day),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun InputBar(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit, onRecord: () -> Unit, onAttach: () -> Unit) {
    val record = withMicPermission(onRecord)
    Row(verticalAlignment = Alignment.Bottom) {
        RoundIconButton(
            Icons.Rounded.Add,
            "Attach",
            onAttach,
            background = solid(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentColor = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        TextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Message") },
            maxLines = 5,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        )
        Spacer(Modifier.width(8.dp))
        // One button that morphs between "record" and "send" as you type.
        AnimatedContent(
            targetState = draft.isNotBlank(),
            transitionSpec = {
                (scaleIn(spring(Spring.DampingRatioMediumBouncy), initialScale = 0.4f) + fadeIn()) togetherWith
                    (scaleOut(targetScale = 0.4f) + fadeOut(tween(90)))
            },
            label = "send",
        ) { typing ->
            RoundIconButton(
                icon = if (typing) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Mic,
                contentDescription = if (typing) "Send" else "Record voice message",
                onClick = if (typing) onSend else record,
                background = LocalGradients.current.sunset,
            )
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
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 0.7f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "rec",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).height(56.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Rounded.Delete, contentDescription = "Cancel recording", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(Modifier.size(10.dp).scale(pulse).background(Sunset.Red, CircleShape))
                Text(formatDuration(elapsed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 10.dp))
                LiveLevels(Modifier.weight(1f).height(28.dp).padding(end = 8.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        RoundIconButton(Icons.AutoMirrored.Rounded.Send, "Send voice message", onSend, background = LocalGradients.current.sunset)
    }
}

/** Bars dancing while recording: a playful "we're listening" indicator. */
@Composable
private fun LiveLevels(modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "levels").animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1_000_000, easing = LinearEasing)),
        label = "t",
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val bars = 16
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val level = 0.25f + 0.75f * ((sin(t * 9f + i * 1.7f) + sin(t * 5.3f + i * 0.9f)) / 4f + 0.5f)
            val h = size.height * level
            drawRoundRect(color, topLeft = Offset(i * (w + gap), (size.height - h) / 2), size = Size(w, h), cornerRadius = CornerRadius(w / 2))
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    playback: Playback?,
    onTogglePlayback: (ChatMessage) -> Unit,
    progress: Float?,
    onOpenPhoto: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mine = message.fromMe
    // Messages that just arrived pop in; older ones (when opening a chat) just appear.
    val pop = remember { Animatable(if (System.currentTimeMillis() - message.sentAt < 3_000) 0.6f else 1f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) }

    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        val shape = if (mine) RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp) else RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
        val background = if (mine) LocalGradients.current.sunset else solid(MaterialTheme.colorScheme.surfaceContainerHigh)
        val contentColor = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
        if (message.isImage) {
            // Photos fill their bubble, with the time on a small pill over the corner.
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                        transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
                    }
                    .clip(shape),
            ) {
                PhotoContent(message, progress, onOpen = onOpenPhoto, onDownload = onDownload)
                Surface(color = Color.Black.copy(alpha = 0.45f), shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatMessageTime(message.sentAt), style = MaterialTheme.typography.labelSmall, color = Color.White)
                        if (mine) StatusIcon(message.status, Color.White)
                    }
                }
            }
            return@Box
        }
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
                }
                .clip(shape)
                .background(background)
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
        ) {
            if (message.isVoice) {
                VoiceNote(message, playback, onTogglePlayback, mine, contentColor, progress, onDownload)
            } else if (message.isFile) {
                FileContent(message, mine, contentColor, progress, onDownload)
            } else {
                Text(message.body, color = contentColor, style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatMessageTime(message.sentAt), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
                if (mine) StatusIcon(message.status, contentColor.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun StatusIcon(status: MessageStatus, tint: Color) {
    val (icon, description) = when (status) {
        MessageStatus.SENDING -> Icons.Rounded.Schedule to "Sending"
        MessageStatus.FAILED -> Icons.Rounded.ErrorOutline to "Failed"
        else -> Icons.Rounded.Done to "Sent"
    }
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.padding(start = 4.dp).size(14.dp))
}

@Composable
private fun VoiceNote(
    message: ChatMessage,
    playback: Playback?,
    onToggle: (ChatMessage) -> Unit,
    mine: Boolean,
    contentColor: Color,
    progress: Float?,
    onDownload: () -> Unit,
) {
    if (message.localPath == null) {
        if (message.unavailable) Text("Voice message no longer available", color = contentColor.copy(alpha = 0.7f))
        else TransferBadge(progress, onDownload, tint = contentColor)
        return
    }
    val duration = message.durationMs ?: 0
    val position = playback?.positionMs ?: 0
    val progress = if (duration > 0 && playback != null) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val playing = playback?.playing == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (mine) solid(Color.White) else LocalGradients.current.sunset)
                .clickable { onToggle(message) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = if (mine) Sunset.Coral else Color.White,
            )
        }
        Column(Modifier.padding(start = 10.dp)) {
            Waveform(message.id, progress, played = contentColor, unplayed = contentColor.copy(alpha = 0.35f), modifier = Modifier.width(150.dp).height(30.dp))
            Text(
                formatDuration(if (playback != null) position else duration),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.8f),
            )
        }
    }
}

/** A stylized waveform (stable per message), filling up as it plays. */
@Composable
private fun Waveform(seed: String, progress: Float, played: Color, unplayed: Color, modifier: Modifier) {
    val levels = remember(seed) { Random(seed.hashCode()).let { r -> List(28) { 0.25f + 0.75f * r.nextFloat() } } }
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (levels.size - 1)) / levels.size
        levels.forEachIndexed { i, level ->
            val h = size.height * level
            val color = if ((i + 0.5f) / levels.size <= progress) played else unplayed
            drawRoundRect(color, topLeft = Offset(i * (w + gap), (size.height - h) / 2), size = Size(w, h), cornerRadius = CornerRadius(w / 2))
        }
    }
}

/** Wraps [action] so it first asks for the microphone permission if needed. */
@Composable
fun withMicPermission(action: () -> Unit): () -> Unit = withCallPermissions(video = false, action)

/**
 * Wraps [action] so it first asks for the microphone (and for video, camera) permission if
 * needed. Only the microphone is required: without the camera, a video call just doesn't send video.
 */
@Composable
fun withCallPermissions(video: Boolean, action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val permissions = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) action()
    }
    return {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else request.launch(missing.toTypedArray())
    }
}
