package one.yago.sorchat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import one.yago.sorchat.app.ChatViewModel
import one.yago.sorchat.app.Contact

@Composable
fun SorchatApp(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.me != null) RequestNotificationPermission()
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val me = state.me
        val chat = state.openChat
        when {
            me == null -> RegisterScreen(state, onRegister = vm::register)
            chat != null -> {
                BackHandler { vm.openChat(null) }
                ChatScreen(
                    contact = state.contacts.firstOrNull { it.id == chat } ?: Contact(chat, chat),
                    messages = state.conversation,
                    connected = state.connected,
                    onSend = vm::send,
                    onBack = { vm.openChat(null) },
                    onVisible = vm::setVisibleChat,
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

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(permission)
        }
    }
}
