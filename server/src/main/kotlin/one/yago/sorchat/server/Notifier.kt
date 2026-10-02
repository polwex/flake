package one.yago.sorchat.server

import org.slf4j.LoggerFactory

/** Wakes a user's device when something is waiting for it while they're offline. */
fun interface Notifier {
    suspend fun messageWaiting(userId: String)
}

/** Placeholder until FCM is wired in: just logs what would be pushed. */
class LogNotifier : Notifier {
    private val log = LoggerFactory.getLogger(LogNotifier::class.java)

    override suspend fun messageWaiting(userId: String) {
        log.info("push → {}: message waiting", userId)
    }
}
