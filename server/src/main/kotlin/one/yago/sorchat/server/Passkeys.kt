package one.yago.sorchat.server

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.ByteArray
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import one.yago.sorchat.protocol.PasskeyOptions
import one.yago.sorchat.protocol.PasskeyResponse
import java.security.SecureRandom
import java.util.Base64
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

class PasskeyException(message: String) : Exception(message)

/**
 * Where passkeys may be used: the relying party (our domain) and the Android app, identified by
 * its package and signing certificate fingerprints (SHA-256, as in `keytool`/`signingReport`).
 */
data class PasskeyConfig(val rpId: String, val androidPackage: String, val certFingerprints: List<String>) {
    private val certHashes = certFingerprints.map { fp -> fp.split(":").map { it.toInt(16).toByte() }.toByteArray() }

    /** WebAuthn origins: Android reports `android:apk-key-hash:<base64url of the cert's SHA-256>`. */
    val origins: Set<String> =
        certHashes.map { "android:apk-key-hash:" + Base64.getUrlEncoder().withoutPadding().encodeToString(it) }.toSet() +
            "https://$rpId"

    /** Served at /.well-known/assetlinks.json: lets the app use passkeys for [rpId]. */
    val assetLinksJson: String = buildJsonArray {
        add(
            buildJsonObject {
                put("relation", JsonArray(listOf("delegate_permission/common.handle_all_urls", "delegate_permission/common.get_login_creds").map(::JsonPrimitive)))
                put("target", buildJsonObject {
                    put("namespace", "android_app")
                    put("package_name", androidPackage)
                    put("sha256_cert_fingerprints", JsonArray(certFingerprints.map { JsonPrimitive(it.uppercase()) }))
                })
            }
        )
    }.toString()
}

/**
 * Passkey registration and sign-in (WebAuthn, verified with Yubico's library). A passkey is a
 * discoverable credential whose user handle is the account id, so signing in needs no username.
 */
class Passkeys(private val store: Store, config: PasskeyConfig) {
    private val rp = RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(config.rpId).name("sorchat").build())
        .credentialRepository(Repository())
        .origins(config.origins)
        .build()
    private val random = SecureRandom()

    private sealed class Pending(val expiresAt: Long)
    private class PendingRegistration(val userId: String, val options: PublicKeyCredentialCreationOptions, expiresAt: Long) : Pending(expiresAt)
    private class PendingLogin(val request: AssertionRequest, expiresAt: Long) : Pending(expiresAt)

    /** Ceremonies in progress, by request id. */
    private val pending = ConcurrentHashMap<String, Pending>()

    suspend fun startRegistration(user: User): PasskeyOptions = withContext(Dispatchers.IO) {
        val options = rp.startRegistration(
            StartRegistrationOptions.builder()
                .user(UserIdentity.builder().name(user.id).displayName(user.name).id(userHandle(user.id)).build())
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build()
                )
                .build()
        )
        PasskeyOptions(remember(PendingRegistration(user.id, options, expiry())), publicKeyJson(options.toCredentialsCreateJson()))
    }

    suspend fun finishRegistration(user: User, response: PasskeyResponse) = withContext(Dispatchers.IO) {
        val registration = take<PendingRegistration>(response.requestId)
        if (registration.userId != user.id) throw PasskeyException("This passkey request belongs to another account")
        val result = try {
            rp.finishRegistration(
                FinishRegistrationOptions.builder()
                    .request(registration.options)
                    .response(PublicKeyCredential.parseRegistrationResponseJson(response.responseJson))
                    .build()
            )
        } catch (e: Exception) {
            throw PasskeyException("Passkey registration failed: ${e.message}")
        }
        store.addPasskey(Passkey(result.keyId.id.base64Url, user.id, result.publicKeyCose.base64Url, result.signatureCount))
    }

    suspend fun startLogin(): PasskeyOptions = withContext(Dispatchers.IO) {
        val request = rp.startAssertion(StartAssertionOptions.builder().userVerification(UserVerificationRequirement.REQUIRED).build())
        PasskeyOptions(remember(PendingLogin(request, expiry())), publicKeyJson(request.toCredentialsGetJson()))
    }

    /** Verifies a sign-in and returns the account's user id. */
    suspend fun finishLogin(response: PasskeyResponse): String = withContext(Dispatchers.IO) {
        val login = take<PendingLogin>(response.requestId)
        val result = try {
            rp.finishAssertion(
                FinishAssertionOptions.builder()
                    .request(login.request)
                    .response(PublicKeyCredential.parseAssertionResponseJson(response.responseJson))
                    .build()
            )
        } catch (e: Exception) {
            throw PasskeyException("Passkey sign-in failed: ${e.message}")
        }
        if (!result.isSuccess) throw PasskeyException("Passkey sign-in failed")
        store.updatePasskeySignCount(result.credentialId.base64Url, result.signatureCount)
        result.username
    }

    private fun remember(request: Pending): String {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { it.value.expiresAt < now }
        val id = kotlin.ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        pending[id] = request
        return id
    }

    private inline fun <reified T : Pending> take(requestId: String): T {
        val request = pending.remove(requestId) as? T
        if (request == null || request.expiresAt < System.currentTimeMillis()) throw PasskeyException("Passkey request expired, try again")
        return request
    }

    private fun expiry() = System.currentTimeMillis() + 5.minutes.inWholeMilliseconds

    /** Yubico wraps options for `navigator.credentials.*` as {"publicKey": …}; Android wants the inner object. */
    private fun publicKeyJson(wrapped: String) = Json.parseToJsonElement(wrapped).jsonObject.getValue("publicKey").toString()

    private fun userHandle(userId: String) = ByteArray(userId.toByteArray())

    /** Yubico's view of our passkeys table. Called from the IO dispatcher, so blocking is fine. */
    private inner class Repository : CredentialRepository {
        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> =
            runBlocking { store.passkeysFor(username) }
                .map { PublicKeyCredentialDescriptor.builder().id(ByteArray.fromBase64Url(it.credentialId)).build() }
                .toSet()

        override fun getUserHandleForUsername(username: String): Optional<ByteArray> =
            Optional.ofNullable(runBlocking { store.findUser(username) }?.let { userHandle(it.id) })

        override fun getUsernameForUserHandle(userHandle: ByteArray): Optional<String> =
            Optional.ofNullable(runBlocking { store.findUser(String(userHandle.bytes)) }?.id)

        override fun lookup(credentialId: ByteArray, userHandle: ByteArray): Optional<RegisteredCredential> =
            Optional.ofNullable(registered(credentialId)?.takeIf { it.userHandle == userHandle })

        override fun lookupAll(credentialId: ByteArray): Set<RegisteredCredential> = setOfNotNull(registered(credentialId))

        private fun registered(credentialId: ByteArray): RegisteredCredential? =
            runBlocking { store.passkey(credentialId.base64Url) }?.let {
                RegisteredCredential.builder()
                    .credentialId(credentialId)
                    .userHandle(userHandle(it.userId))
                    .publicKeyCose(ByteArray.fromBase64Url(it.publicKeyCose))
                    .signatureCount(it.signCount)
                    .build()
            }
    }
}

