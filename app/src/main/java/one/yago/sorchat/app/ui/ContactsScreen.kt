package one.yago.sorchat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import one.yago.sorchat.app.Contact
import one.yago.sorchat.app.Identity
import one.yago.sorchat.app.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: UiState,
    me: Identity,
    onAddContact: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    var newContact by rememberSaveable { mutableStateOf("") }
    fun add() {
        onAddContact(newContact)
        newContact = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("sorchat") },
                actions = { ConnectionIndicator(state.connected, Modifier.padding(end = 16.dp)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Signed in as ${me.name}", style = MaterialTheme.typography.titleMedium)
                    Text("Share your ID so people can add you:")
                    SelectionContainer {
                        Text(me.id, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newContact,
                    onValueChange = { newContact = it },
                    label = { Text("Contact ID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = ::add, enabled = newContact.isNotBlank() && !state.busy) { Text("Add") }
            }

            state.error?.let {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissError) { Text("OK") }
                }
            }

            if (state.contacts.isEmpty()) {
                Text(
                    "No contacts yet. Add someone by their ID.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(state.contacts, key = Contact::id) { contact ->
                    val last = state.messages[contact.id]?.lastOrNull()
                    ListItem(
                        headlineContent = { Text(contact.name) },
                        supportingContent = { Text(last?.body ?: contact.id, maxLines = 1) },
                        modifier = Modifier.clickable { onOpenChat(contact.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
