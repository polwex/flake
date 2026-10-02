package one.yago.sorchat.app

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/** Receives FCM wake-ups. The pushes carry no content; the messages are fetched over the WebSocket. */
class PushService : FirebaseMessagingService() {
    /** FCM issued a (new) token for this install, after [FirebaseMessaging.register] or a rotation. */
    override fun onRegistered(token: String) {
        ChatRepository.get(this).onPushToken(token)
    }

    // Runs on a background thread, and the process may be killed shortly after it returns,
    // so the sync happens synchronously here.
    override fun onMessageReceived(message: RemoteMessage) {
        runBlocking { ChatRepository.get(this@PushService).syncFromPush() }
    }
}
