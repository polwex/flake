package one.yago.sorchat.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import one.yago.sorchat.protocol.Attachment
import one.yago.sorchat.protocol.CallSignal
import one.yago.sorchat.protocol.ClientFrame
import one.yago.sorchat.protocol.EndReason
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.PushTokenRequest
import one.yago.sorchat.protocol.RegisterRequest
import one.yago.sorchat.protocol.RegisterResponse
import one.yago.sorchat.protocol.ServerFrame
import one.yago.sorchat.protocol.UploadResponse
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MessagingTest {
    private fun ApplicationTestBuilder.setUp(pushed: MutableList<String>): HttpClient {
        application { sorchat(Store.open(":memory:"), { userId, _ -> pushed += userId; true }, createTempDirectory().toFile()) }
        return createClient {
            install(ContentNegotiation) { json(ProtocolJson) }
            install(WebSockets)
        }
    }

    private suspend fun HttpClient.register(name: String): RegisterResponse =
        post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(name))
        }.body()

    private suspend fun DefaultClientWebSocketSession.send(frame: ClientFrame) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientFrame.serializer(), frame)))

    private suspend fun DefaultClientWebSocketSession.receiveFrame(): ServerFrame = withTimeout(5_000) {
        val frame = incoming.receive() as Frame.Text
        ProtocolJson.decodeFromString(ServerFrame.serializer(), frame.readText())
    }

    @Test
    fun `message to an offline user is queued, pushed, delivered on connect and deleted after ack`() = testApplication {
        val pushed = mutableListOf<String>()
        val client = setUp(pushed)
        val alice = client.register("Alice")
        val bob = client.register("Bob")

        client.webSocket("/ws", request = { bearerAuth(alice.token) }) {
            assertEquals(ServerFrame.Synced, receiveFrame())
            send(ClientFrame.Send("m1", bob.userId, "hi bob"))
            assertEquals(ServerFrame.Accepted("m1"), receiveFrame())
            // A retry with the same id is accepted again but not stored twice.
            send(ClientFrame.Send("m1", bob.userId, "hi bob"))
            assertEquals(ServerFrame.Accepted("m1"), receiveFrame())
        }
        assertEquals(listOf(bob.userId), pushed)

        client.webSocket("/ws", request = { bearerAuth(bob.token) }) {
            val message = receiveFrame() as ServerFrame.Message
            assertEquals("m1", message.id)
            assertEquals(alice.userId, message.from)
            assertEquals("Alice", message.fromName)
            assertEquals("hi bob", message.body)
            assertEquals(ServerFrame.Synced, receiveFrame())
            send(ClientFrame.Ack("m1"))
        }

        // After the ack, a reconnect delivers nothing old: the queue is empty, then a live message arrives.
        client.webSocket("/ws", request = { bearerAuth(bob.token) }) {
            assertEquals(ServerFrame.Synced, receiveFrame())
            client.webSocket("/ws", request = { bearerAuth(alice.token) }) {
                assertEquals(ServerFrame.Synced, receiveFrame())
                send(ClientFrame.Send("m2", bob.userId, "still there?"))
                assertEquals(ServerFrame.Accepted("m2"), receiveFrame())
            }
            assertEquals("m2", (receiveFrame() as ServerFrame.Message).id)
        }
    }

    @Test
    fun `unknown recipient and bad token are rejected`() = testApplication {
        val client = setUp(mutableListOf())
        val alice = client.register("Alice")

        client.webSocket("/ws", request = { bearerAuth(alice.token) }) {
            assertEquals(ServerFrame.Synced, receiveFrame())
            send(ClientFrame.Send("m1", "nobody", "hello?"))
            assertEquals(ServerFrame.Error("m1", "unknown recipient"), receiveFrame())
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/users/${alice.userId}") { bearerAuth("wrong") }.status)
    }

    @Test
    fun `push token is stored per user`() = testApplication {
        val store = Store.open(":memory:")
        application { sorchat(store, { _, _ -> true }, createTempDirectory().toFile()) }
        val client = createClient { install(ContentNegotiation) { json(ProtocolJson) } }
        val alice = client.register("Alice")

        val response = client.put("/push-token") {
            bearerAuth(alice.token)
            contentType(ContentType.Application.Json)
            setBody(PushTokenRequest("fcm-token-1"))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("fcm-token-1", store.pushToken(alice.userId))

        // A stale token is only cleared if it's still the current one.
        store.clearPushToken(alice.userId, "older-token")
        assertEquals("fcm-token-1", store.pushToken(alice.userId))
        store.clearPushToken(alice.userId, "fcm-token-1")
        assertEquals(null, store.pushToken(alice.userId))
    }

    @Test
    fun `a file is delivered with its message and deleted once the recipient has it`() = testApplication {
        val mediaDir = createTempDirectory().toFile()
        application { sorchat(Store.open(":memory:"), { _, _ -> true }, mediaDir) }
        val client = createClient {
            install(ContentNegotiation) { json(ProtocolJson) }
            install(WebSockets)
        }
        val alice = client.register("Alice")
        val bob = client.register("Bob")
        val audio = ByteArray(5_000) { it.toByte() }

        val upload: UploadResponse = client.post("/media") {
            bearerAuth(alice.token)
            setBody(ByteArrayContent(audio, ContentType.parse("audio/ogg")))
        }.body()

        client.webSocket("/ws", request = { bearerAuth(alice.token) }) {
            assertEquals(ServerFrame.Synced, receiveFrame())
            // The client's claims about the file are replaced by the server's record.
            send(ClientFrame.Send("v1", bob.userId, "", Attachment(upload.mediaId, "audio/fake", 1, durationMs = 1_200, name = "../../etc/notes.ogg")))
            assertEquals(ServerFrame.Accepted("v1"), receiveFrame())
            send(ClientFrame.Send("v2", bob.userId, "", Attachment("0".repeat(32), "audio/ogg", 1)))
            assertEquals(ServerFrame.Error("v2", "unknown attachment"), receiveFrame())
        }

        client.webSocket("/ws", request = { bearerAuth(bob.token) }) {
            val attachment = (receiveFrame() as ServerFrame.Message).attachment!!
            // Path components are stripped from the name.
            assertEquals(Attachment(upload.mediaId, "audio/ogg", audio.size.toLong(), 1_200, name = "notes.ogg"), attachment)
            assertEquals(ServerFrame.Synced, receiveFrame())
            // The message is acked right away; the file is fetched afterwards (possibly much later).
            send(ClientFrame.Ack("v1"))
        }

        val charlie = client.register("Charlie")
        assertEquals(HttpStatusCode.NotFound, client.get("/media/${upload.mediaId}") { bearerAuth(charlie.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/media/${upload.mediaId}") { bearerAuth(alice.token) }.status)

        val download = client.get("/media/${upload.mediaId}") { bearerAuth(bob.token) }
        assertEquals(ContentType.parse("audio/ogg"), download.contentType())
        assertContentEquals(audio, download.readRawBytes())
        assertEquals(HttpStatusCode.NoContent, client.delete("/media/${upload.mediaId}") { bearerAuth(bob.token) }.status)

        assertEquals(HttpStatusCode.NotFound, client.get("/media/${upload.mediaId}") { bearerAuth(bob.token) }.status)
        assertEquals(emptyList(), mediaDir.list()!!.toList())
    }

    @Test
    fun `uploads respect the file size limit and the storage quota`() = testApplication {
        val limits = MediaLimits(maxFileBytes = 1_000, quotaBytes = 1_500)
        application { sorchat(Store.open(":memory:"), { _, _ -> true }, createTempDirectory().toFile(), mediaLimits = limits) }
        val client = createClient { install(ContentNegotiation) { json(ProtocolJson) } }
        val alice = client.register("Alice")
        suspend fun upload(size: Int, type: ContentType = ContentType.Application.Pdf) =
            client.post("/media") {
                bearerAuth(alice.token)
                setBody(ByteArrayContent(ByteArray(size), type))
            }.status

        assertEquals(HttpStatusCode.PayloadTooLarge, upload(1_200))
        assertEquals(HttpStatusCode.OK, upload(800))
        assertEquals(HttpStatusCode.OK, upload(500, ContentType.Image.JPEG))
        // 1300 of 1500 bytes used: a 300-byte file no longer fits.
        assertEquals(HttpStatusCode.InsufficientStorage, upload(300))
    }

    @Test
    fun `call signals are relayed between connected users`() = testApplication {
        val client = setUp(mutableListOf())
        val alice = client.register("Alice")
        val bob = client.register("Bob")

        client.webSocket("/ws", request = { bearerAuth(alice.token) }) {
            val aliceWs = this
            assertEquals(ServerFrame.Synced, aliceWs.receiveFrame())
            client.webSocket("/ws", request = { bearerAuth(bob.token) }) {
                val bobWs = this
                assertEquals(ServerFrame.Synced, bobWs.receiveFrame())
                aliceWs.send(ClientFrame.Call(bob.userId, "c1", CallSignal.Invite("offer-sdp")))
                assertEquals(ServerFrame.Call(alice.userId, "Alice", "c1", CallSignal.Invite("offer-sdp")), bobWs.receiveFrame())
                bobWs.send(ClientFrame.Call(alice.userId, "c1", CallSignal.Accept("answer-sdp")))
                assertEquals(ServerFrame.Call(bob.userId, "Bob", "c1", CallSignal.Accept("answer-sdp")), aliceWs.receiveFrame())
            }
        }
    }

    @Test
    fun `a call to an offline user pushes them and is held until they connect`() = testApplication {
        val pushes = mutableListOf<Pair<String, Push>>()
        val store = Store.open(":memory:")
        application { sorchat(store, { userId, push -> pushes += userId to push; userId != "nobody" }, createTempDirectory().toFile()) }
        val client = createClient {
            install(ContentNegotiation) { json(ProtocolJson) }
            install(WebSockets)
        }
        val alice = client.register("Alice")
        val bob = client.register("Bob")
        val ice = CallSignal.Ice("0", 0, "candidate:1")

        client.webSocket("/ws", request = { bearerAuth(alice.token) }) {
            val aliceWs = this
            assertEquals(ServerFrame.Synced, aliceWs.receiveFrame())
            aliceWs.send(ClientFrame.Call(bob.userId, "c1", CallSignal.Invite("offer-sdp")))
            aliceWs.send(ClientFrame.Call(bob.userId, "c1", ice))

            // Bob's device wakes up and connects: it gets the invite and the candidate sent meanwhile.
            client.webSocket("/ws", request = { bearerAuth(bob.token) }) {
                val bobWs = this
                assertEquals(ServerFrame.Call(alice.userId, "Alice", "c1", CallSignal.Invite("offer-sdp")), bobWs.receiveFrame())
                assertEquals(ServerFrame.Call(alice.userId, "Alice", "c1", ice), bobWs.receiveFrame())
                assertEquals(ServerFrame.Synced, bobWs.receiveFrame())
                bobWs.send(ClientFrame.Call(alice.userId, "c1", CallSignal.Accept("answer-sdp")))
                assertEquals(ServerFrame.Call(bob.userId, "Bob", "c1", CallSignal.Accept("answer-sdp")), aliceWs.receiveFrame())
            }
            assertEquals(listOf<Pair<String, Push>>(bob.userId to Push.IncomingCall("c1", alice.userId, "Alice", video = false)), pushes)

            // A call the caller gives up on before the callee connects: the callee is told to stop ringing.
            aliceWs.send(ClientFrame.Call(bob.userId, "c2", CallSignal.Invite("offer-sdp")))
            aliceWs.send(ClientFrame.Call(bob.userId, "c2", CallSignal.End(EndReason.HANGUP)))
            client.webSocket("/ws", request = { bearerAuth(bob.token) }) {
                assertEquals(ServerFrame.Synced, receiveFrame())
            }
            assertEquals(bob.userId to Push.CallEnded("c2"), pushes.last())
        }
    }

    @Test
    fun `a call to someone without a push token is unavailable`() = testApplication {
        application { sorchat(Store.open(":memory:"), { _, _ -> false }, createTempDirectory().toFile()) }
        val client = createClient {
            install(ContentNegotiation) { json(ProtocolJson) }
            install(WebSockets)
        }
        val alice = client.register("Alice")
        val bob = client.register("Bob")
        client.webSocket("/ws", request = { bearerAuth(alice.token) }) {
            assertEquals(ServerFrame.Synced, receiveFrame())
            send(ClientFrame.Call(bob.userId, "c1", CallSignal.Invite("offer-sdp")))
            assertEquals(ServerFrame.Call(bob.userId, "Bob", "c1", CallSignal.End(EndReason.UNAVAILABLE)), receiveFrame())
        }
    }

    @Test
    fun `TURN credentials follow coturn's shared-secret scheme`() {
        val ice = IceConfig(listOf("turn:turn.example.com:3478"), secret = "s3cret", now = { 1_000_000_000_000 })
        val server = ice.serversFor("alice").single()
        // expiry = now + 24h, in seconds
        assertEquals("1000086400:alice", server.username)
        val mac = javax.crypto.Mac.getInstance("HmacSHA1").apply { init(javax.crypto.spec.SecretKeySpec("s3cret".toByteArray(), "HmacSHA1")) }
        assertEquals(java.util.Base64.getEncoder().encodeToString(mac.doFinal("1000086400:alice".toByteArray())), server.credential)
        assertEquals(listOf("stun:stun.l.google.com:19302"), IceConfig().serversFor("alice").single().urls)
    }
}
