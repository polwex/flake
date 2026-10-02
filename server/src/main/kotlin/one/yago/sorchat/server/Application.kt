package one.yago.sorchat.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.PushTokenRequest
import one.yago.sorchat.protocol.RegisterRequest
import one.yago.sorchat.protocol.UserInfo
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Undelivered messages are dropped after this long. */
private val MESSAGE_TTL = 30.days

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val dbPath = System.getenv("SORCHAT_DB") ?: "data/sorchat.db"
    val store = Store.open(dbPath)
    // Firebase service-account key, given as JSON content or as a file path; without it, pushes are only logged.
    val fcmCredentials = System.getenv("SORCHAT_FCM_CREDENTIALS_JSON")?.takeIf { it.isNotBlank() }
        ?: System.getenv("SORCHAT_FCM_CREDENTIALS")?.takeIf { it.isNotBlank() }?.let { File(it).readText() }
    val notifier = fcmCredentials?.let { FcmNotifier(store, it) } ?: LogNotifier()
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        log.info("Push notifications via {}", notifier::class.simpleName)
        sorchat(store, notifier)
    }.start(wait = true)
}

fun Application.sorchat(store: Store, notifier: Notifier) {
    val hub = Hub(store, notifier)

    install(ContentNegotiation) { json(ProtocolJson) }
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
    }

    launch {
        while (true) {
            store.deleteMessagesOlderThan(System.currentTimeMillis() - MESSAGE_TTL.inWholeMilliseconds)
            delay(1.hours)
        }
    }

    suspend fun ApplicationCall.user(): User? {
        val header = request.headers["Authorization"] ?: return null
        val token = header.removePrefix("Bearer ").takeIf { it != header } ?: return null
        return store.findUserByToken(token)
    }

    routing {
        post("/register") {
            val name = call.receive<RegisterRequest>().name.trim()
            if (name.isEmpty() || name.length > 64) {
                call.respond(HttpStatusCode.BadRequest, "name must be 1-64 characters")
                return@post
            }
            call.respond(store.createUser(name))
        }

        put("/push-token") {
            val user = call.user()
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@put
            }
            store.setPushToken(user.id, call.receive<PushTokenRequest>().token)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/users/{id}") {
            if (call.user() == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
            val user = store.findUser(call.parameters["id"]!!)
            if (user == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(UserInfo(user.id, user.name))
        }

        webSocket("/ws") {
            val user = call.user()
            if (user == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }
            hub.serve(user, this)
        }
    }
}
