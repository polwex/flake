package one.yago.sorchat.server

import one.yago.sorchat.protocol.IceServer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Hands out WebRTC ICE servers. With a coturn secret, each user gets TURN credentials in coturn's
 * `use-auth-secret` scheme: username `<expiry>:<userId>`, password base64(HMAC-SHA1(secret, username)).
 */
class IceConfig(
    private val urls: List<String> = emptyList(),
    private val secret: String? = null,
    private val ttl: Duration = 24.hours,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun serversFor(userId: String): List<IceServer> {
        if (secret == null || urls.isEmpty()) return listOf(IceServer(listOf(FALLBACK_STUN)))
        val username = "${(now() + ttl.inWholeMilliseconds) / 1000}:$userId"
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }
        val credential = Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray()))
        return listOf(IceServer(urls, username, credential))
    }

    fun describe(): String = if (secret == null || urls.isEmpty()) "$FALLBACK_STUN (no TURN)" else urls.joinToString()

    private companion object {
        const val FALLBACK_STUN = "stun:stun.l.google.com:19302"
    }
}
