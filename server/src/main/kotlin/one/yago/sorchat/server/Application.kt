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
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
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
import one.yago.sorchat.protocol.LoginResponse
import one.yago.sorchat.protocol.PasskeyResponse
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.PushTokenRequest
import one.yago.sorchat.protocol.RegisterRequest
import one.yago.sorchat.protocol.UploadResponse
import one.yago.sorchat.protocol.UserInfo

/** Undelivered messages are dropped after this long. */
private val MESSAGE_TTL = 30.days

fun main() {
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val dbPath = System.getenv("SORCHAT_DB") ?: "data/sorchat.db"
    val mediaDir = File(System.getenv("SORCHAT_MEDIA_DIR") ?: "data/media")
    val mediaLimits = MediaLimits(
        maxFileBytes = (System.getenv("SORCHAT_MAX_FILE_MB")?.toLong() ?: 25) * 1024 * 1024,
        quotaBytes = (System.getenv("SORCHAT_MEDIA_QUOTA_MB")?.toLong() ?: 1024) * 1024 * 1024,
        ttl = (System.getenv("SORCHAT_MEDIA_TTL_DAYS")?.toLong() ?: 14).days,
    )
    val store = Store.open(dbPath)
    // Firebase service-account key, given as JSON content or as a file path; without it, pushes are only logged.
    val fcmCredentials = System.getenv("SORCHAT_FCM_CREDENTIALS_JSON")?.takeIf { it.isNotBlank() }
        ?: System.getenv("SORCHAT_FCM_CREDENTIALS")?.takeIf { it.isNotBlank() }?.let { File(it).readText() }
    val notifier = fcmCredentials?.let { FcmNotifier(store, it) } ?: LogNotifier()
    // coturn, e.g. SORCHAT_TURN_URLS="stun:turn.example.com:3478,turn:turn.example.com:3478,turns:turn.example.com:5349"
    // with SORCHAT_TURN_SECRET (or a file holding it, SORCHAT_TURN_SECRET_FILE) matching its static-auth-secret.
    // Without it, only a public STUN server is offered.
    val ice = IceConfig(
        urls = System.getenv("SORCHAT_TURN_URLS")?.split(",")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
        secret = (System.getenv("SORCHAT_TURN_SECRET") ?: System.getenv("SORCHAT_TURN_SECRET_FILE")?.let { File(it).readText() })
            ?.trim()?.takeIf { it.isNotEmpty() },
    )
    // Passkeys need the domain the app talks to and the app's signing certificate fingerprints, e.g.
    // SORCHAT_PASSKEY_RP_ID=chat.example.com SORCHAT_ANDROID_CERT_SHA256=AB:CD:…[,…]
    val passkeys = System.getenv("SORCHAT_PASSKEY_RP_ID")?.takeIf { it.isNotBlank() }?.let { rpId ->
        val fingerprints = System.getenv("SORCHAT_ANDROID_CERT_SHA256")?.split(",")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        PasskeyConfig(rpId, System.getenv("SORCHAT_ANDROID_PACKAGE") ?: "one.yago.sorchat", fingerprints)
    }
    embeddedServer(Netty, port = port, host = host) {
        log.info("Push notifications via {}", notifier::class.simpleName)
        log.info("ICE servers: {}", ice.describe())
        log.info("Passkeys: {}", passkeys?.let { "for ${it.rpId}, ${it.certFingerprints.size} app certificate(s)" } ?: "disabled")
        log.info("Media: files up to {} MB, {} MB in total, kept {}", mediaLimits.maxFileBytes shr 20, mediaLimits.quotaBytes shr 20, mediaLimits.ttl)
        sorchat(store, notifier, mediaDir, ice, passkeys, mediaLimits)
    }.start(wait = true)
}

fun Application.sorchat(
    store: Store,
    notifier: Notifier,
    mediaDir: File,
    ice: IceConfig = IceConfig(),
    passkeyConfig: PasskeyConfig? = null,
    mediaLimits: MediaLimits = MediaLimits(),
) {
    val passkeys = passkeyConfig?.let { Passkeys(store, it) }
    val media = MediaStore(mediaDir, store, mediaLimits)
    val hub = Hub(store, media, notifier)

    install(ContentNegotiation) { json(ProtocolJson) }
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        exception<PasskeyException> { call, e -> call.respond(HttpStatusCode.BadRequest, e.message ?: "passkey error") }
    }

    launch {
        while (true) {
            val cutoff = System.currentTimeMillis() - MESSAGE_TTL.inWholeMilliseconds
            store.deleteMessagesOlderThan(cutoff)
            media.deleteExpired()
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
            val type = call.request.contentType().takeIf { it != ContentType.Any }?.withoutParameters() ?: ContentType.Application.OctetStream
            val declaredSize = call.request.headers["Content-Length"]?.toLongOrNull()
            val result = call.receiveChannel().toInputStream().use { media.save(user.id, type.toString(), it, declaredSize) }
            when (result) {
                is SaveResult.Saved -> call.respond(UploadResponse(result.id))
                SaveResult.TooLarge -> call.respond(HttpStatusCode.PayloadTooLarge, "Files can be up to ${media.limits.maxFileBytes shr 20} MB")
                SaveResult.QuotaFull -> call.respond(HttpStatusCode.InsufficientStorage, "The server's storage is full, try again later")
            }
        }

        get("/media/{id}") {
            val user = call.user()
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
            // Only the uploader and the recipient; to everyone else the file doesn't exist.
            val found = media.find(call.parameters["id"]!!)?.takeIf { it.owner == user.id || it.recipient == user.id }
            if (found == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(LocalFileContent(media.file(found), ContentType.parse(found.mimeType)))
        }

        // The recipient has the file: the server's copy can go.
        delete("/media/{id}") {
            val user = call.user()
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@delete
            }
            val found = media.find(call.parameters["id"]!!)?.takeIf { it.recipient == user.id }
            if (found == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                media.delete(found.id)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // Digital Asset Links: lets the Android app use passkeys for this domain.
        get("/.well-known/assetlinks.json") {
            if (passkeyConfig == null) call.respond(HttpStatusCode.NotFound)
            else call.respondText(passkeyConfig.assetLinksJson, ContentType.Application.Json)
        }

        post("/passkey/register/start") {
            val user = call.user()
            when {
                passkeys == null -> call.respond(HttpStatusCode.NotFound)
                user == null -> call.respond(HttpStatusCode.Unauthorized)
                else -> call.respond(passkeys.startRegistration(user))
            }
        }

        post("/passkey/register/finish") {
            val user = call.user()
            when {
                passkeys == null -> call.respond(HttpStatusCode.NotFound)
                user == null -> call.respond(HttpStatusCode.Unauthorized)
                else -> {
                    passkeys.finishRegistration(user, call.receive<PasskeyResponse>())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        post("/passkey/login/start") {
            if (passkeys == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(passkeys.startLogin())
        }

        // Signing in with a passkey issues a new token, so the account moves to this device.
        post("/passkey/login/finish") {
            if (passkeys == null) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            val userId = passkeys.finishLogin(call.receive<PasskeyResponse>())
            val user = checkNotNull(store.findUser(userId))
            call.respond(LoginResponse(user.id, user.name, store.issueToken(user.id)))
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
