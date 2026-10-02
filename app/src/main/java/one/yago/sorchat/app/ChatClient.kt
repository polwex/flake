package one.yago.sorchat.app

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import one.yago.sorchat.protocol.ClientFrame
import one.yago.sorchat.protocol.IceServer
import one.yago.sorchat.protocol.IceServersResponse
import one.yago.sorchat.protocol.LoginResponse
import one.yago.sorchat.protocol.PasskeyOptions
import one.yago.sorchat.protocol.PasskeyResponse
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.PushTokenRequest
import one.yago.sorchat.protocol.RegisterRequest
import one.yago.sorchat.protocol.RegisterResponse
import one.yago.sorchat.protocol.ServerFrame
import one.yago.sorchat.protocol.UploadResponse
import one.yago.sorchat.protocol.UserInfo
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** The server refused an upload, with a message for the user (file too big, storage full). */
class UploadRejectedException(message: String) : Exception(message)

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

    suspend fun registerPushToken(token: String, pushToken: String) {
        http.put("$baseUrl/push-token") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PushTokenRequest(pushToken))
        }
    }

    suspend fun passkeyRegisterStart(token: String): PasskeyOptions =
        http.post("$baseUrl/passkey/register/start") { bearerAuth(token) }.body()

    suspend fun passkeyRegisterFinish(token: String, response: PasskeyResponse) {
        http.post("$baseUrl/passkey/register/finish") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(response)
        }
    }

    suspend fun passkeyLoginStart(): PasskeyOptions = http.post("$baseUrl/passkey/login/start").body()

    suspend fun passkeyLoginFinish(response: PasskeyResponse): LoginResponse =
        http.post("$baseUrl/passkey/login/finish") {
            contentType(ContentType.Application.Json)
            setBody(response)
        }.body()

    suspend fun iceServers(token: String): List<IceServer> =
        http.get("$baseUrl/ice-servers") { bearerAuth(token) }.body<IceServersResponse>().servers

    /**
     * Uploads a file for use as an attachment, streaming it from disk. Returns its media id.
     * Throws [UploadRejectedException] if the server won't take it (too big, storage full).
     */
    suspend fun upload(token: String, file: File, mimeType: String, onProgress: (Float) -> Unit = {}): String {
        val length = file.length()
        val body = object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.parse(mimeType)
            override val contentLength = length

            override suspend fun writeTo(channel: ByteWriteChannel) {
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                withContext(Dispatchers.IO) { file.inputStream() }.use { input ->
                    while (true) {
                        val n = withContext(Dispatchers.IO) { input.read(buffer) }
                        if (n < 0) break
                        channel.writeFully(buffer, 0, n)
                        sent += n
                        onProgress(sent.toFloat() / length.coerceAtLeast(1))
                    }
                }
            }
        }
        return try {
            http.post("$baseUrl/media") {
                bearerAuth(token)
                setBody(body)
            }.body<UploadResponse>().mediaId
        } catch (e: ResponseException) {
            when (e.response.status) {
                HttpStatusCode.PayloadTooLarge, HttpStatusCode.InsufficientStorage -> throw UploadRejectedException(e.response.bodyAsText())
                else -> throw e
            }
        }
    }

    /**
     * Downloads an attachment to [dest], streaming it to disk. Returns false if the server no
     * longer has it.
     */
    suspend fun download(token: String, mediaId: String, dest: File, onProgress: (Float) -> Unit = {}): Boolean {
        // Written under a temporary name so a partial download is never mistaken for the file.
        val partial = File(dest.path + ".part")
        try {
            http.prepareGet("$baseUrl/media/$mediaId") { bearerAuth(token) }.execute { response ->
                val total = response.contentLength() ?: -1L
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var received = 0L
                withContext(Dispatchers.IO) { partial.outputStream() }.use { out ->
                    while (true) {
                        val n = channel.readAvailable(buffer, 0, buffer.size)
                        if (n < 0) break
                        withContext(Dispatchers.IO) { out.write(buffer, 0, n) }
                        received += n
                        if (total > 0) onProgress(received.toFloat() / total)
                    }
                }
            }
        } catch (e: ClientRequestException) {
            partial.delete()
            if (e.response.status == HttpStatusCode.NotFound) return false else throw e
        }
        check(partial.renameTo(dest)) { "Couldn't move download to $dest" }
        return true
    }

    /** Tells the server we have the file, so it can delete its copy. */
    suspend fun confirmDownload(token: String, mediaId: String) {
        try {
            http.delete("$baseUrl/media/$mediaId") { bearerAuth(token) }
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.NotFound) throw e
        }
    }

    /** Closes the current connection gracefully, after anything already queued has been sent. */
    suspend fun disconnect() {
        session?.close()
    }

    /**
     * Opens one WebSocket and processes frames until it closes.
     * Throws [UnauthorizedException] if the server rejects our token.
     */
    suspend fun session(
        token: String,
        onConnected: suspend () -> Unit,
        onFrame: suspend (ServerFrame) -> Unit,
    ) {
        http.webSocket(urlString = baseUrl.replaceFirst("http", "ws") + "/ws", request = { bearerAuth(token) }) {
            session = this
            _connected.value = true
            try {
                onConnected()
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val parsed = try {
                        ProtocolJson.decodeFromString(ServerFrame.serializer(), frame.readText())
                    } catch (e: SerializationException) {
                        Log.w(TAG, "Ignoring frame this version doesn't understand", e)
                        continue
                    }
                    onFrame(parsed)
                }
                if (closeReason.await()?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
                    throw UnauthorizedException()
                }
            } finally {
                session = null
                _connected.value = false
            }
        }
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
        var backoff = 1.seconds
        while (true) {
            try {
                session(
                    token,
                    onConnected = {
                        backoff = 1.seconds
                        onConnected()
                    },
                    onFrame = onFrame,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection failed, retrying in $backoff", e)
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30.seconds)
        }
    }

    private companion object {
        const val TAG = "ChatClient"
    }
}
