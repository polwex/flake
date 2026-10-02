package one.yago.sorchat.app.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.yago.sorchat.app.ChatMessage
import one.yago.sorchat.app.Contact
import one.yago.sorchat.app.Identity
import one.yago.sorchat.app.UiState
import one.yago.sorchat.app.preview
import one.yago.sorchat.app.ui.theme.LocalGradients
import one.yago.sorchat.app.ui.theme.Sunset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: UiState,
    me: Identity,
    onAddContact: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onDismissError: () -> Unit,
    onCreatePasskey: () -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    // Most recent conversation first; contacts without messages after, by name.
    val contacts = remember(state.contacts, state.lastMessages) {
        state.contacts.sortedWith(compareByDescending<Contact> { state.lastMessages[it.id]?.sentAt ?: 0L }.thenBy { it.name.lowercase() })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            SunsetButton(
                text = "New chat",
                icon = Icons.Rounded.PersonAdd,
                onClick = { adding = true },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item(key = "header") { Header(me, state.connected) }

            item(key = "passkey") {
                AnimatedVisibility(!state.hasPasskey, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    PasskeyCard(busy = state.busy, onCreate = onCreatePasskey)
                }
            }

            state.error?.let { error ->
                item(key = "error") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                            TextButton(onClick = onDismissError) { Text("OK") }
                        }
                    }
                }
            }

            if (contacts.isEmpty()) {
                item(key = "empty") { EmptyState() }
            } else {
                item(key = "title") {
                    Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
                }
            }
            items(contacts, key = Contact::id) { contact ->
                ContactRow(contact, state.lastMessages[contact.id], onClick = { onOpenChat(contact.id) }, modifier = Modifier.animateItem())
            }
        }
    }

    if (adding) {
        ModalBottomSheet(onDismissRequest = { adding = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            AddContactSheet(onAdd = {
                onAddContact(it)
                adding = false
            })
        }
    }
}

@Composable
private fun Header(me: Identity, connected: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
            .background(LocalGradients.current.sunset)
            .statusBarsPadding()
            .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("sorchat", style = MaterialTheme.typography.headlineLarge, color = Color.White, modifier = Modifier.weight(1f))
                StatusPill(connected)
            }
            Text("Hi, ${me.name} 👋", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.2f), contentColor = Color.White) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your ID", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.width(8.dp))
                    Text(me.id, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                    IconButton(onClick = {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("sorchat ID", me.id))) }
                    }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy ID") }
                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Come chat with me on sorchat! My ID is ${me.id}")
                        context.startActivity(Intent.createChooser(share, "Share your ID"))
                    }) { Icon(Icons.Rounded.Share, contentDescription = "Share ID") }
                }
            }
        }
    }
}

@Composable
fun StatusPill(connected: Boolean, modifier: Modifier = Modifier) {
    // While connecting, the dot breathes.
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.6f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.2f), contentColor = Color.White, modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .scale(if (connected) 1f else pulse)
                    .background(if (connected) Sunset.Green else Color.White, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (connected) "online" else "connecting…", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PasskeyCard(busy: Boolean, onCreate: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(LocalGradients.current.sunset), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Protect your account", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add a passkey so you can get back in after reinstalling or on a new phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
            TextButton(onClick = onCreate, enabled = !busy) { Text("Add") }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, last: ChatMessage?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .squishy(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact.name, contact.id, size = 54.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = last?.let { (if (it.fromMe) "You: " else "") + it.preview() } ?: "Say hi 👋",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        last?.let { Text(formatListTime(it.sentAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BobbingLogo(size = 120.dp)
        Text("No chats yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Share your ID with friends, or tap New chat and enter theirs.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddContactSheet(onAdd: (String) -> Unit) {
    var id by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Start a new chat", style = MaterialTheme.typography.headlineSmall)
        Text("Ask your friend for their sorchat ID: they'll find it at the top of their app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = id,
            onValueChange = { id = it.trim().lowercase() },
            placeholder = { Text("e.g. hfgvb8wp") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (id.isNotBlank()) onAdd(id) }),
            modifier = Modifier.fillMaxWidth(),
        )
        SunsetButton("Add", onClick = { onAdd(id) }, enabled = id.isNotBlank(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}
