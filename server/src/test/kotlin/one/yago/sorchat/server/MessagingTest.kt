package one.yago.sorchat.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import one.yago.sorchat.protocol.ClientFrame
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.PushTokenRequest
import one.yago.sorchat.protocol.RegisterRequest
import one.yago.sorchat.protocol.RegisterResponse
import one.yago.sorchat.protocol.ServerFrame
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagingTest {
    private fun ApplicationTestBuilder.setUp(pushed: MutableList<String>): HttpClient {
        application { sorchat(Store.open(":memory:")) { pushed += it } }
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
        application { sorchat(store) {} }
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
}
