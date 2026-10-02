package one.yago.sorchat.app

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.yago.sorchat.protocol.ClientFrame
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.RegisterRequest
import one.yago.sorchat.protocol.RegisterResponse
import one.yago.sorchat.protocol.ServerFrame
import one.yago.sorchat.protocol.UserInfo
import kotlin.time.Duration.Companion.seconds

/** The server no longer accepts our token (e.g. its database was reset). */
class UnauthorizedException : Exception("Server rejected our credentials")

/** Talks to the sorchat server: HTTP for account calls, one WebSocket for messaging. */
class ChatClient(private val baseUrl: String) {
    private val http = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) { json(ProtocolJson) }
        install(WebSockets) { pingIntervalMillis = 20_000 }
    }

    @Volatile
    private var session: DefaultWebSocketSession? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    suspend fun register(name: String): RegisterResponse =
        http.post("$baseUrl/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(name))
        }.body()

    /** Returns null if no user has this id. */
    suspend fun lookUp(token: String, userId: String): UserInfo? =
        try {
            http.get("$baseUrl/users/$userId") { bearerAuth(token) }.body()
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) null else throw e
        }

    /** Sends a frame if connected. Returns false if it couldn't be handed to the socket. */
    fun send(frame: ClientFrame): Boolean {
        val text = ProtocolJson.encodeToString(ClientFrame.serializer(), frame)
        return session?.outgoing?.trySend(Frame.Text(text))?.isSuccess ?: false
    }

    /**
     * Keeps a WebSocket open, reconnecting with exponential backoff, until the calling coroutine
     * is cancelled. Throws [UnauthorizedException] if the server rejects our token.
     */
    suspend fun run(
        token: String,
        onConnected: suspend () -> Unit,
        onFrame: suspend (ServerFrame) -> Unit,
    ) {
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/ws"
        var backoff = 1.seconds
        while (true) {
            try {
                http.webSocket(urlString = wsUrl, request = { bearerAuth(token) }) {
                    session = this
                    _connected.value = true
                    backoff = 1.seconds
                    onConnected()
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        onFrame(ProtocolJson.decodeFromString(ServerFrame.serializer(), frame.readText()))
                    }
                    if (closeReason.await()?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
                        throw UnauthorizedException()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection failed, retrying in $backoff", e)
            } finally {
                session = null
                _connected.value = false
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30.seconds)
        }
    }

    private companion object {
        const val TAG = "ChatClient"
    }
}
