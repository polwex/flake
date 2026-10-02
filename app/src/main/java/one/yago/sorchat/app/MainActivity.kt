package one.yago.sorchat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import one.yago.sorchat.app.ui.SorchatApp

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { SorchatApp(vm) }
        // Show over the lock screen and wake the display while a call rings or is ongoing.
        lifecycleScope.launch {
            vm.call.collect { call ->
                val calling = call != null && call.phase != CallPhase.ENDED
                setShowWhenLocked(calling)
                setTurnScreenOn(calling)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        // Tapping a message notification opens that conversation.
        intent.getStringExtra(EXTRA_CHAT)?.let(vm::openChat)
        // "Answer" on the incoming-call notification. Without the microphone permission, the call
        // screen's own Accept button asks for it first.
        if (intent.action == ACTION_ANSWER &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            vm.acceptCall()
        }
    }

    companion object {
        const val EXTRA_CHAT = "one.yago.sorchat.CHAT"
        const val ACTION_ANSWER = "one.yago.sorchat.ANSWER"
    }
}
