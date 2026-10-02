package one.yago.sorchat.server

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/** What a push tells the device. Pushes carry no message content. */
sealed interface Push {
    /** Messages are queued; the app should connect and fetch them. */
    data object MessagesWaiting : Push

    /** Someone is calling; the app should ring and connect to get the call details. */
    data class IncomingCall(val callId: String, val callerId: String, val callerName: String) : Push

    /** The caller gave up before the call was picked up; stop ringing. */
    data class CallEnded(val callId: String) : Push
}

/** Wakes a user's device while it isn't connected. */
fun interface Notifier {
    /** Returns false if the user can't be reached (no push token registered). */
    suspend fun push(userId: String, push: Push): Boolean
}

/** Used when FCM isn't configured: just logs what would be pushed. */
class LogNotifier : Notifier {
    private val log = LoggerFactory.getLogger(LogNotifier::class.java)

    override suspend fun push(userId: String, push: Push): Boolean {
        log.info("push → {}: {} (FCM not configured)", userId, push)
        return true
    }
}

/**
 * Sends a data-only, high-priority FCM message through the HTTP v1 API. The push carries no
 * content: it only wakes the app, which then fetches the queued messages over its WebSocket.
 */
class FcmNotifier(private val store: Store, credentialsJson: String) : Notifier {
    private val log = LoggerFactory.getLogger(FcmNotifier::class.java)
    private val credentials: GoogleCredentials
    private val projectId: String
    private val http = HttpClient(CIO)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        val account = ServiceAccountCredentials.fromStream(credentialsJson.byteInputStream())
        credentials = account.createScoped("https://www.googleapis.com/auth/firebase.messaging")
        projectId = requireNotNull(account.projectId) { "FCM credentials have no project_id" }
    }

    override suspend fun push(userId: String, push: Push): Boolean {
        val token = store.pushToken(userId)
        if (token == null) {
            log.info("push → {}: no push token registered", userId)
            return false
        }
        // Don't hold up the sender's connection on a round trip to Google.
        scope.launch {
            runCatching { send(userId, token, push) }.onFailure { log.warn("push → {} failed", userId, it) }
        }
        return true
    }

    private suspend fun send(userId: String, token: String, push: Push) {
        val accessToken = synchronized(credentials) {
            credentials.refreshIfExpired()
            checkNotNull(credentials.accessToken) { "Google returned no access token" }.tokenValue
        }
        val body = buildJsonObject {
            putJsonObject("message") {
                put("token", token)
                putJsonObject("data") {
                    when (push) {
                        Push.MessagesWaiting -> put("type", "messages")
                        is Push.IncomingCall -> {
                            put("type", "call")
                            put("callId", push.callId)
                            put("from", push.callerId)
                            put("fromName", push.callerName)
                        }
                        is Push.CallEnded -> {
                            put("type", "call_end")
                            put("callId", push.callId)
                        }
                    }
                }
                putJsonObject("android") {
                    put("priority", "HIGH")
                    if (push == Push.MessagesWaiting) {
                        // Several pushes while the device is offline collapse into one wake-up.
                        put("collapse_key", "messages")
                    } else {
                        // A call that couldn't be delivered quickly is over anyway.
                        put("ttl", "30s")
                    }
                }
            }
        }
        val response = http.post("https://fcm.googleapis.com/v1/projects/$projectId/messages:send") {
            bearerAuth(accessToken)
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        when {
            response.status.isSuccess() -> log.info("push → {}: {} sent", userId, push)
            response.status == HttpStatusCode.NotFound -> {
                // UNREGISTERED: the app was uninstalled or the token rotated.
                store.clearPushToken(userId, token)
                log.info("push → {}: token no longer valid, forgot it", userId)
            }
            else -> log.warn("push → {} failed: {} {}", userId, response.status, response.bodyAsText())
        }
    }
}
