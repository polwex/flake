package one.yago.sorchat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import one.yago.sorchat.app.Call
import one.yago.sorchat.app.ChatViewModel
import one.yago.sorchat.app.Contact
import one.yago.sorchat.app.ui.theme.SorchatTheme

/** Which screen is showing; drives the transitions between them. */
private sealed interface Screen {
    data object Welcome : Screen
    data object Contacts : Screen
    data class Chat(val contactId: String) : Screen
    data object Calling : Screen
}

@Composable
fun SorchatApp(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Passkey prompts need an Activity to show over.
    val activity = checkNotNull(LocalActivity.current)
    val playback by vm.playback.collectAsStateWithLifecycle()
    val call by vm.call.collectAsStateWithLifecycle()
    val localVideo by vm.localVideo.collectAsStateWithLifecycle()
    val remoteVideo by vm.remoteVideo.collectAsStateWithLifecycle()
    if (state.me != null) RequestNotificationPermission()

    // The call screen keeps showing the last call while it animates away.
    var lastCall by remember { mutableStateOf<Call?>(null) }
    call?.let { lastCall = it }

    val screen = when {
        call != null -> Screen.Calling
        state.me == null -> Screen.Welcome
        state.openChat != null -> Screen.Chat(state.openChat!!)
        else -> Screen.Contacts
    }

    SorchatTheme {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { transition(initialState, targetState) },
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            label = "screen",
        ) { shown ->
            when (shown) {
                Screen.Calling -> lastCall?.let { current ->
                    CallScreen(
                        call = current,
                        video = CallVideo(vm.eglContext, localVideo, remoteVideo),
                        onCameraOn = vm::setCameraOn,
                        onSwitchCamera = vm::switchCamera,
                        onAccept = vm::acceptCall,
                        onDecline = vm::declineCall,
                        onHangUp = vm::hangUp,
                        onMute = vm::setMuted,
                        onSpeaker = vm::setSpeaker,
                    )
                }
                Screen.Welcome -> WelcomeScreen(
                    state,
                    onRegister = { vm.register(it, activity) },
                    onSignIn = { vm.signInWithPasskey(activity) },
                )
                is Screen.Chat -> {
                    BackHandler { vm.openChat(null) }
                    ChatScreen(
                        contact = state.contacts.firstOrNull { it.id == shown.contactId } ?: Contact(shown.contactId, shown.contactId),
                        messages = state.conversation,
                        connected = state.connected,
                        error = state.error,
                        onDismissError = vm::dismissError,
                        onSend = vm::send,
                        onCall = vm::startCall,
                        onBack = { vm.openChat(null) },
                        onVisible = vm::setVisibleChat,
                        voice = VoiceControls(
                            recordingSince = state.recordingSince,
                            playback = playback,
                            onStartRecording = vm::startRecording,
                            onCancelRecording = vm::cancelRecording,
                            onFinishRecording = vm::finishRecording,
                            onTogglePlayback = vm::togglePlayback,
                        ),
                    )
                }
                Screen.Contacts -> state.me?.let { me ->
                    ContactsScreen(
                        state = state,
                        me = me,
                        onAddContact = vm::addContact,
                        onOpenChat = vm::openChat,
                        onDismissError = vm::dismissError,
                        onCreatePasskey = { vm.createPasskey(activity) },
                    )
                }
            }
        }
    }
}

private val bouncy = spring<Float>(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)

private fun transition(from: Screen, to: Screen): ContentTransform = when {
    // Calls pop in and out like a bubble.
    to == Screen.Calling -> (scaleIn(bouncy, initialScale = 0.85f) + fadeIn()) togetherWith fadeOut(tween(150))
    from == Screen.Calling -> fadeIn(tween(250)) togetherWith (scaleOut(targetScale = 0.9f) + fadeOut(tween(200)))
    // Into a chat slides in from the right; back slides it out again.
    to is Screen.Chat -> slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn() togetherWith
        slideOutHorizontally { -it / 4 } + fadeOut()
    from is Screen.Chat -> slideInHorizontally { -it / 4 } + fadeIn() togetherWith
        slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeOut()
    else -> fadeIn(tween(300)) togetherWith fadeOut(tween(200))
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
