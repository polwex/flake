package one.yago.sorchat.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import one.yago.sorchat.protocol.LoginResponse
import one.yago.sorchat.protocol.PasskeyOptions
import one.yago.sorchat.protocol.PasskeyResponse
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.RegisterRequest
import one.yago.sorchat.protocol.RegisterResponse
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val RP_ID = "chat.example.com"
private const val CERT = "02:AE:F3:83:0F:1D:1D:6D:9F:15:1B:AC:3D:7A:61:0C:6E:C2:5F:EE:D0:69:BF:94:80:C3:78:03:1A:E1:90:99"

class PasskeyTest {
    private val config = PasskeyConfig(RP_ID, "one.yago.sorchat", listOf(CERT))

    @Test
    fun `create a passkey, then sign in with it on a new device`() = testApplication {
        application { sorchat(Store.open(":memory:"), { _, _ -> true }, createTempDirectory().toFile(), passkeyConfig = config) }
        val client = createClient { install(ContentNegotiation) { json(ProtocolJson) } }
        val account: RegisterResponse = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("Alice"))
        }.body()
        val authenticator = SoftAuthenticator(origin = config.origins.first { it.startsWith("android:") })

        val createOptions: PasskeyOptions = client.post("/passkey/register/start") { bearerAuth(account.token) }.body()
        val created = client.post("/passkey/register/finish") {
            bearerAuth(account.token)
            contentType(ContentType.Application.Json)
            setBody(PasskeyResponse(createOptions.requestId, authenticator.create(createOptions.optionsJson)))
        }
        assertEquals(HttpStatusCode.NoContent, created.status, created.bodyAsText())

        // New phone: no token, just the (synced) passkey.
        val loginOptions: PasskeyOptions = client.post("/passkey/login/start").body()
        val login: LoginResponse = client.post("/passkey/login/finish") {
            contentType(ContentType.Application.Json)
            setBody(PasskeyResponse(loginOptions.requestId, authenticator.get(loginOptions.optionsJson)))
        }.body()
        assertEquals(account.userId, login.userId)
        assertEquals("Alice", login.name)
        assertNotEquals(account.token, login.token)

        // The new token works; the old device's doesn't.
        assertEquals(HttpStatusCode.OK, client.get("/ice-servers") { bearerAuth(login.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/ice-servers") { bearerAuth(account.token) }.status)
    }

    @Test
    fun `a passkey from another app or a replayed request is rejected`() = testApplication {
        application { sorchat(Store.open(":memory:"), { _, _ -> true }, createTempDirectory().toFile(), passkeyConfig = config) }
        val client = createClient { install(ContentNegotiation) { json(ProtocolJson) } }
        val account: RegisterResponse = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("Alice"))
        }.body()
        val impostor = SoftAuthenticator(origin = "android:apk-key-hash:not-our-app")
        val options: PasskeyOptions = client.post("/passkey/register/start") { bearerAuth(account.token) }.body()
        val response = PasskeyResponse(options.requestId, impostor.create(options.optionsJson))
        val rejected = client.post("/passkey/register/finish") {
            bearerAuth(account.token)
            contentType(ContentType.Application.Json)
            setBody(response)
        }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        // The request was used up by the failed attempt.
        val replayed = client.post("/passkey/register/finish") {
            bearerAuth(account.token)
            contentType(ContentType.Application.Json)
            setBody(response)
        }
        assertEquals(HttpStatusCode.BadRequest, replayed.status)
    }

    @Test
    fun `asset links name the app and its certificate`() = testApplication {
        application { sorchat(Store.open(":memory:"), { _, _ -> true }, createTempDirectory().toFile(), passkeyConfig = config) }
        val links = Json.parseToJsonElement(client.get("/.well-known/assetlinks.json").bodyAsText()).jsonArray.single().jsonObject
        val target = links.getValue("target").jsonObject
        assertEquals("one.yago.sorchat", target.getValue("package_name").jsonPrimitive.content)
        assertEquals(CERT, target.getValue("sha256_cert_fingerprints").jsonArray.single().jsonPrimitive.content)
        assertTrue("delegate_permission/common.get_login_creds" in links.getValue("relation").toString())
    }
}

/** Just enough of a WebAuthn authenticator (ES256, "none" attestation) to exercise the server. */
private class SoftAuthenticator(private val origin: String) {
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val credentialId = ByteArray(16).also(SecureRandom()::nextBytes)
    private lateinit var userHandle: ByteArray
    private var counter = 0

    fun create(optionsJson: String): String {
        val options = Json.parseToJsonElement(optionsJson).jsonObject
        userHandle = b64d(options.getValue("user").jsonObject.getValue("id").jsonPrimitive.content)
        val clientData = clientData("webauthn.create", options.getValue("challenge").jsonPrimitive.content)
        val pub = keys.public as ECPublicKey
        val cose = cbor(mapOf(1 to 2, 3 to -7, -1 to 1, -2 to unsigned32(pub.w.affineX), -3 to unsigned32(pub.w.affineY)))
        val authData = rpIdHash() + byteArrayOf(0x45) + int32(counter) + ByteArray(16) +
            byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()) + credentialId + cose
        val attestation = cbor(mapOf("fmt" to "none", "attStmt" to emptyMap<Any, Any>(), "authData" to authData))
        return credential {
            put("clientDataJSON", b64(clientData))
            put("attestationObject", b64(attestation))
        }
    }

    fun get(optionsJson: String): String {
        val options = Json.parseToJsonElement(optionsJson).jsonObject
        val clientData = clientData("webauthn.get", options.getValue("challenge").jsonPrimitive.content)
        val authData = rpIdHash() + byteArrayOf(0x05) + int32(++counter)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(authData + sha256(clientData))
            sign()
        }
        return credential {
            put("clientDataJSON", b64(clientData))
            put("authenticatorData", b64(authData))
            put("signature", b64(signature))
            put("userHandle", b64(userHandle))
        }
    }

    private fun credential(response: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("id", b64(credentialId))
        put("rawId", b64(credentialId))
        put("type", "public-key")
        putJsonObject("response", response)
        putJsonObject("clientExtensionResults") {}
    }.toString()

    private fun clientData(type: String, challenge: String) =
        buildJsonObject {
            put("type", type)
            put("challenge", challenge)
            put("origin", origin)
        }.toString().toByteArray()

    private fun rpIdHash() = sha256(RP_ID.toByteArray())
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
private fun b64d(s: String) = Base64.getUrlDecoder().decode(s)
private fun int32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
private fun unsigned32(n: BigInteger) = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

/** Minimal CBOR encoder: maps, ints, text and byte strings (COSE keys need integer map keys). */
private fun cbor(value: Any): ByteArray = ByteArrayOutputStream().also { cbor(value, it) }.toByteArray()

private fun cbor(value: Any, out: ByteArrayOutputStream) {
    fun head(major: Int, n: Long) {
        when {
            n < 24 -> out.write((major shl 5) or n.toInt())
            n < 256 -> { out.write((major shl 5) or 24); out.write(n.toInt()) }
            else -> { out.write((major shl 5) or 25); out.write((n shr 8).toInt()); out.write(n.toInt() and 0xff) }
        }
    }
    when (value) {
        is Int -> if (value >= 0) head(0, value.toLong()) else head(1, (-1 - value).toLong())
        is String -> value.toByteArray().let { head(3, it.size.toLong()); out.write(it) }
        is ByteArray -> { head(2, value.size.toLong()); out.write(value) }
        is Map<*, *> -> { head(5, value.size.toLong()); value.forEach { (k, v) -> cbor(k!!, out); cbor(v!!, out) } }
        else -> error("Unsupported CBOR value $value")
    }
}
