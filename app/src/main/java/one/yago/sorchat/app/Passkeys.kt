package one.yago.sorchat.app

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import one.yago.sorchat.protocol.LoginResponse
import one.yago.sorchat.protocol.PasskeyResponse

/** Turns the server's "no passkey support configured" (404) into a readable error. */
private suspend fun <T> serverCall(call: suspend () -> T): T = try {
    call()
} catch (e: ClientRequestException) {
    if (e.response.status == HttpStatusCode.NotFound) throw IllegalStateException("Passkeys aren't enabled on the server yet") else throw e
}

/** The user dismissed the passkey prompt; not an error worth showing. */
class PasskeyCancelledException : Exception("Cancelled")

/**
 * Passkeys through Android's Credential Manager (synced by the user's password manager, e.g.
 * Google Password Manager). The server's WebAuthn JSON is passed through unchanged.
 */
class Passkeys(private val client: ChatClient) {
    /** Creates a passkey for the signed-in account. */
    suspend fun create(activity: Activity, token: String) {
        val options = serverCall { client.passkeyRegisterStart(token) }
        val response = try {
            CredentialManager.create(activity).createCredential(activity, CreatePublicKeyCredentialRequest(options.optionsJson))
        } catch (_: CreateCredentialCancellationException) {
            throw PasskeyCancelledException()
        }
        val json = (response as CreatePublicKeyCredentialResponse).registrationResponseJson
        client.passkeyRegisterFinish(token, PasskeyResponse(options.requestId, json))
    }

    /** Signs in with a passkey the user picks. */
    suspend fun signIn(activity: Activity): LoginResponse {
        val options = serverCall { client.passkeyLoginStart() }
        val result = try {
            CredentialManager.create(activity).getCredential(
                activity,
                GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options.optionsJson))),
            )
        } catch (_: GetCredentialCancellationException) {
            throw PasskeyCancelledException()
        } catch (_: NoCredentialException) {
            throw IllegalStateException("No sorchat passkey on this device")
        }
        val json = (result.credential as PublicKeyCredential).authenticationResponseJson
        return client.passkeyLoginFinish(PasskeyResponse(options.requestId, json))
    }
}
