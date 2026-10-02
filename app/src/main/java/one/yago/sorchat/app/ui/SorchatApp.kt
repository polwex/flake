package one.yago.sorchat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import one.yago.sorchat.app.ChatViewModel

@Composable
fun SorchatApp(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val me = state.me
        val chat = state.openChat
        when {
            me == null -> RegisterScreen(state, onRegister = vm::register)
            chat != null -> {
                BackHandler { vm.openChat(null) }
                ChatScreen(
                    contact = state.contacts.first { it.id == chat },
                    messages = state.messages[chat].orEmpty(),
                    connected = state.connected,
                    onSend = vm::send,
                    onBack = { vm.openChat(null) },
                )
            }
            else -> ContactsScreen(
                state = state,
                me = me,
                onAddContact = vm::addContact,
                onOpenChat = vm::openChat,
                onDismissError = vm::dismissError,
            )
        }
    }
}
