package one.yago.sorchat.app.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import one.yago.sorchat.app.ChatMessage
import one.yago.sorchat.app.Contact
import one.yago.sorchat.app.MessageStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contact: Contact,
    messages: List<ChatMessage>,
    connected: Boolean,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
    onVisible: (String?) -> Unit,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Send") }
            }
        },
    ) { padding ->
        // Reversed so the list starts at the bottom and stays pinned to the newest message.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages.asReversed(), key = ChatMessage::id) { MessageBubble(it) }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
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
                Text(message.body)
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
fun ConnectionIndicator(connected: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(if (connected) Color(0xFF2E7D32) else Color(0xFFC62828), CircleShape))
        Text(if (connected) "online" else "offline", style = MaterialTheme.typography.labelMedium)
    }
}
