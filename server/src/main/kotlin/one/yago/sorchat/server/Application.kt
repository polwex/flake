package one.yago.sorchat.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.yago.sorchat.protocol.IceServersResponse
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.PushTokenRequest
import one.yago.sorchat.protocol.RegisterRequest
import one.yago.sorchat.protocol.UploadResponse
import one.yago.sorchat.protocol.UserInfo

/** Undelivered messages are dropped after this long. */
private val MESSAGE_TTL = 30.days

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val dbPath = System.getenv("SORCHAT_DB") ?: "data/sorchat.db"
    val mediaDir = File(System.getenv("SORCHAT_MEDIA_DIR") ?: "data/media")
    val store = Store.open(dbPath)
    // Firebase service-account key, given as JSON content or as a file path; without it, pushes are only logged.
    val fcmCredentials = System.getenv("SORCHAT_FCM_CREDENTIALS_JSON")?.takeIf { it.isNotBlank() }
        ?: System.getenv("SORCHAT_FCM_CREDENTIALS")?.takeIf { it.isNotBlank() }?.let { File(it).readText() }
    val notifier = fcmCredentials?.let { FcmNotifier(store, it) } ?: LogNotifier()
    // coturn, e.g. SORCHAT_TURN_URLS="stun:turn.example.com:3478,turn:turn.example.com:3478,turns:turn.example.com:5349"
    // with SORCHAT_TURN_SECRET matching its static-auth-secret. Without it, only a public STUN server is offered.
    val ice = IceConfig(
        urls = System.getenv("SORCHAT_TURN_URLS")?.split(",")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
        secret = System.getenv("SORCHAT_TURN_SECRET")?.takeIf { it.isNotBlank() },
    )
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        log.info("Push notifications via {}", notifier::class.simpleName)
        log.info("ICE servers: {}", ice.describe())
        sorchat(store, notifier, mediaDir, ice)
    }.start(wait = true)
}

fun Application.sorchat(store: Store, notifier: Notifier, mediaDir: File, ice: IceConfig = IceConfig()) {
    val media = MediaStore(mediaDir, store)
    val hub = Hub(store, media, notifier)

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
            val cutoff = System.currentTimeMillis() - MESSAGE_TTL.inWholeMilliseconds
            store.deleteMessagesOlderThan(cutoff)
            media.deleteOlderThan(cutoff)
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

        post("/media") {
            val user = call.user()
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            val type = call.request.contentType().withoutParameters()
            if (type.contentType != "audio") {
                call.respond(HttpStatusCode.UnsupportedMediaType)
                return@post
            }
            val id = call.receiveChannel().toInputStream().use { media.save(user.id, type.toString(), it) }
            if (id == null) call.respond(HttpStatusCode.PayloadTooLarge)
            else call.respond(UploadResponse(id))
        }

        get("/media/{id}") {
            if (call.user() == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
            val found = media.find(call.parameters["id"]!!)
            if (found == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(LocalFileContent(media.file(found), ContentType.parse(found.mimeType)))
        }

        get("/ice-servers") {
            val user = call.user()
            if (user == null) call.respond(HttpStatusCode.Unauthorized)
            else call.respond(IceServersResponse(ice.serversFor(user.id)))
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
