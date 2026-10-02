package one.yago.sorchat.loadtest

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import one.yago.sorchat.protocol.Attachment
import one.yago.sorchat.protocol.ClientFrame
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.RegisterRequest
import one.yago.sorchat.protocol.RegisterResponse
import one.yago.sorchat.protocol.ServerFrame
import one.yago.sorchat.protocol.UploadResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Load test for a sorchat server: many fake users connected at once, sending each other messages
 * (and optionally files) at a fixed rate, measuring delivery latency end to end.
 *
 *   ./gradlew :loadtest:run --args="--server http://localhost:8080 --users 200 --rate 50 --duration 60"
 *
 * It creates real accounts that can't be deleted, so point it at a throwaway instance.
 */
fun main(args: Array<String>) {
    val options = try {
        Options.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("${e.message}\n\n${Options.USAGE}")
        exitProcess(2)
    }
    val ok = runBlocking(Dispatchers.Default) { LoadTest(options).run() }
    exitProcess(if (ok) 0 else 1)
}

data class Options(
    val server: String = "http://localhost:8080",
    val users: Int = 50,
    /** Text messages per second, across all users. */
    val rate: Double = 10.0,
    val durationSeconds: Int = 60,
    /** Seconds over which the users connect, so the server isn't hit by all handshakes at once. */
    val rampSeconds: Int = 10,
    /** Text message size in bytes. */
    val messageBytes: Int = 100,
    /** File uploads per second, across all users (0 = none). */
    val uploadRate: Double = 0.0,
    val uploadKb: Int = 300,
) {
    companion object {
        const val USAGE = """Options:
  --server URL        server to test (default http://localhost:8080)
  --users N           simulated users, all connected at once (default 50)
  --rate R            text messages per second in total (default 10)
  --duration S        seconds of sending, after ramp-up (default 60)
  --ramp S            seconds to connect all users over (default 10)
  --message-bytes B   size of each text message (default 100)
  --upload-rate R     file uploads per second in total, e.g. 0.5 (default 0: none)
  --upload-kb K       size of each uploaded file (default 300, about a phone photo)"""

        fun parse(args: Array<String>): Options {
            var o = Options()
            var i = 0
            fun value(): String = args.getOrNull(++i) ?: throw IllegalArgumentException("${args[i - 1]} needs a value")
            while (i < args.size) {
                o = when (args[i]) {
                    "--server" -> o.copy(server = value().trimEnd('/'))
                    "--users" -> o.copy(users = value().toInt())
                    "--rate" -> o.copy(rate = value().toDouble())
                    "--duration" -> o.copy(durationSeconds = value().toInt())
                    "--ramp" -> o.copy(rampSeconds = value().toInt())
                    "--message-bytes" -> o.copy(messageBytes = value().toInt())
                    "--upload-rate" -> o.copy(uploadRate = value().toDouble())
                    "--upload-kb" -> o.copy(uploadKb = value().toInt())
                    "--help", "-h" -> throw IllegalArgumentException("sorchat load test")
                    else -> throw IllegalArgumentException("Unknown option ${args[i]}")
                }
                i++
            }
            require(o.users >= 2) { "Need at least 2 users" }
            return o
        }
    }
}

/** Latency samples (ms) for one reporting interval, plus running totals. */
private class Metric(val name: String) {
    private val samples = ConcurrentLinkedQueue<Long>()
    private val all = ConcurrentLinkedQueue<Long>()

    fun record(ms: Long) {
        samples += ms
        all += ms
    }

    fun drainSummary(): String = summarize(generateSequence { samples.poll() }.toList())

    fun totalSummary(): String = summarize(all.toList())

    private fun summarize(values: List<Long>): String {
        if (values.isEmpty()) return "$name: -"
        val sorted = values.sorted()
        fun p(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
        return "$name: n=${sorted.size} p50=${p(0.5)}ms p95=${p(0.95)}ms p99=${p(0.99)}ms max=${sorted.last()}ms"
    }
}

private class User(val account: RegisterResponse) {
    @Volatile
    var session: DefaultClientWebSocketSession? = null
    val outgoing = Channel<ClientFrame>(Channel.UNLIMITED)
}

class LoadTest(private val o: Options) {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(ProtocolJson) }
        install(WebSockets) { pingIntervalMillis = 20_000 }
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
        engine {
            // The defaults (100 per host) would cap the number of simulated users.
            maxConnectionsCount = 100_000
            endpoint.maxConnectionsPerRoute = 100_000
        }
        expectSuccess = true
    }
    private val wsUrl = o.server.replaceFirst("http", "ws") + "/ws"

    private val delivery = Metric("delivery")
    private val upload = Metric("upload")
    private val download = Metric("download")
    private val connected = AtomicInteger()
    private val sent = AtomicLong()
    private val accepted = AtomicLong()
    private val delivered = AtomicLong()
    private val errors = AtomicLong()
    private val disconnects = AtomicLong()
    private val connectErrors = AtomicLong()
    @Volatile
    private var lastConnectError: String? = null
    /** Sent text messages not delivered yet: id → nanoTime sent. */
    private val inFlight = ConcurrentHashMap<String, Long>()
    private val firstErrors = ConcurrentLinkedQueue<String>()

    suspend fun run(): Boolean {
        println("sorchat load test: ${o.users} users, ${o.rate} msg/s, ${o.uploadRate} uploads/s of ${o.uploadKb} KB, ${o.durationSeconds}s against ${o.server}")
        val users = register()
        println("Registered ${users.size} users. Connecting over ${o.rampSeconds}s…")
        return kotlinx.coroutines.coroutineScope {
            // In the background scope, not this one: a connection stuck somewhere mustn't keep the
            // run from ending. main() exits the process after the summary, which ends them all.
            users.forEachIndexed { i, user ->
                background.launch {
                    delay(o.rampSeconds * 1000L * i / users.size)
                    connect(user)
                }
            }
            val reporter = launch { report() }
            waitForConnections(users.size)
            println("Connected: ${connected.get()}/${users.size}. Sending for ${o.durationSeconds}s…")
            if (connected.get() < users.size) println("  (not everyone could connect; last failure: ${lastConnectError ?: "none"})")

            val senders = buildList {
                add(launch { sendTexts(users) })
                if (o.uploadRate > 0) add(launch { sendFiles(users) })
            }
            senders.forEach { it.join() }
            // Let the last messages arrive before counting what's missing.
            withTimeoutOrNull(10_000) { while (inFlight.isNotEmpty()) delay(100) }
            reporter.cancel()
            summary()
        }
    }

    private suspend fun register(): List<User> {
        val limit = Semaphore(32)
        return kotlinx.coroutines.coroutineScope {
            (1..o.users).map { i ->
                async {
                    limit.withPermit {
                        User(
                            http.post("${o.server}/register") {
                                contentType(ContentType.Application.Json)
                                setBody(RegisterRequest("load-$i"))
                            }.body<RegisterResponse>()
                        )
                    }
                }
            }.awaitAll()
        }
    }

    /** One user's connection: forwards [User.outgoing], acks and times whatever arrives. Reconnects if dropped. */
    private suspend fun connect(user: User) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try {
                val socket = withTimeoutOrNull(15_000) { http.webSocketSession(wsUrl) { bearerAuth(user.account.token) } }
                if (socket == null) connectFailed("timed out") else with(socket) {
                    user.session = this
                    connected.incrementAndGet()
                    val writer = launch { for (frame in user.outgoing) send(Frame.Text(ProtocolJson.encodeToString(ClientFrame.serializer(), frame))) }
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            when (val msg = ProtocolJson.decodeFromString(ServerFrame.serializer(), frame.readText())) {
                                is ServerFrame.Message -> {
                                    user.outgoing.send(ClientFrame.Ack(msg.id))
                                    onMessage(user, msg)
                                }
                                is ServerFrame.Accepted -> accepted.incrementAndGet()
                                is ServerFrame.Error -> error("server error: ${msg.reason}")
                                else -> Unit
                            }
                        }
                    } finally {
                        writer.cancel()
                        user.session = null
                        connected.decrementAndGet()
                    }
                    disconnects.incrementAndGet()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                connectFailed(e.message ?: e::class.simpleName ?: "error")
            }
            delay(1_000)
        }
    }

    private suspend fun onMessage(user: User, msg: ServerFrame.Message) {
        val sentAt = inFlight.remove(msg.id)
        if (sentAt != null) {
            delivery.record((System.nanoTime() - sentAt) / 1_000_000)
            delivered.incrementAndGet()
        }
        val attachment = msg.attachment ?: return
        // Like the app: fetch the file, then tell the server it can delete it.
        background.launch {
            try {
                val start = System.nanoTime()
                http.get("${o.server}/media/${attachment.mediaId}") { bearerAuth(user.account.token) }.readRawBytes()
                download.record((System.nanoTime() - start) / 1_000_000)
                http.delete("${o.server}/media/${attachment.mediaId}") { bearerAuth(user.account.token) }
            } catch (e: Exception) {
                error("download: ${e.message}")
            }
        }
    }

    /** Fire-and-forget work (downloads, uploads) that mustn't hold up the connection or the pacing. */
    private val background = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    private suspend fun waitForConnections(total: Int) {
        withTimeoutOrNull((o.rampSeconds + 30) * 1000L) { while (connected.get() < total) delay(100) }
    }

    private suspend fun sendTexts(users: List<User>) = paced(o.rate) {
        val (from, to) = pair(users)
        val id = UUID.randomUUID().toString()
        inFlight[id] = System.nanoTime()
        sent.incrementAndGet()
        from.outgoing.send(ClientFrame.Send(id, to.account.userId, "x".repeat(o.messageBytes)))
    }

    private suspend fun sendFiles(users: List<User>) = paced(o.uploadRate) {
        val (from, to) = pair(users)
        background.launch {
            try {
                val bytes = Random.nextBytes(o.uploadKb * 1024)
                val start = System.nanoTime()
                val media = http.post("${o.server}/media") {
                    bearerAuth(from.account.token)
                    setBody(ByteArrayContent(bytes, ContentType.Image.JPEG))
                }.body<UploadResponse>()
                upload.record((System.nanoTime() - start) / 1_000_000)
                val id = UUID.randomUUID().toString()
                from.outgoing.send(ClientFrame.Send(id, to.account.userId, "", Attachment(media.mediaId, "image/jpeg", bytes.size.toLong())))
            } catch (e: Exception) {
                error("upload: ${e.message}")
            }
        }
    }

    /** Calls [action] [perSecond] times a second, evenly spaced, for the test duration. */
    private suspend fun paced(perSecond: Double, action: suspend () -> Unit) {
        val interval = (1_000_000_000 / perSecond).toLong()
        val start = System.nanoTime()
        var next = start
        val end = start + o.durationSeconds * 1_000_000_000L
        while (next < end) {
            val wait = (next - System.nanoTime()) / 1_000_000
            if (wait > 0) delay(wait)
            action()
            next += interval
        }
    }

    private fun pair(users: List<User>): Pair<User, User> {
        val a = Random.nextInt(users.size)
        var b = Random.nextInt(users.size - 1)
        if (b >= a) b++
        return users[a] to users[b]
    }

    private fun connectFailed(reason: String) {
        connectErrors.incrementAndGet()
        lastConnectError = reason
    }

    private fun error(message: String?) {
        errors.incrementAndGet()
        if (firstErrors.size < 10) firstErrors += message ?: "unknown"
    }

    private suspend fun report() {
        val start = System.currentTimeMillis()
        while (true) {
            delay(5_000)
            val t = (System.currentTimeMillis() - start) / 1000
            println(
                "[${t}s] connected=${connected.get()} connect-failures=${connectErrors.get()} sent=${sent.get()} delivered=${delivered.get()} " +
                    "in-flight=${inFlight.size} errors=${errors.get()} | ${delivery.drainSummary()}" +
                    (if (o.uploadRate > 0) " | ${upload.drainSummary()} | ${download.drainSummary()}" else "")
            )
        }
    }

    private fun summary(): Boolean {
        val lost = inFlight.size
        println()
        println("=== Summary ===")
        println("Messages: sent=${sent.get()} accepted=${accepted.get()} delivered=${delivered.get()} lost=$lost")
        println(delivery.totalSummary())
        if (o.uploadRate > 0) {
            println(upload.totalSummary())
            println(download.totalSummary())
        }
        println("Errors: ${errors.get()}, reconnects: ${disconnects.get()}, failed connection attempts: ${connectErrors.get()}")
        lastConnectError?.let { println("  last connection failure: $it") }
        firstErrors.forEach { println("  - $it") }
        return lost == 0 && errors.get() == 0L && connectErrors.get() == 0L
    }
}
