package one.yago.sorchat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import one.yago.sorchat.app.ui.SorchatApp

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openChatFrom(intent)
        setContent { SorchatApp(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openChatFrom(intent)
    }

    /** Tapping a message notification opens that conversation. */
    private fun openChatFrom(intent: Intent) {
        intent.getStringExtra(EXTRA_CHAT)?.let(vm::openChat)
    }

    companion object {
        const val EXTRA_CHAT = "one.yago.sorchat.CHAT"
    }
}
