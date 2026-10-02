package one.yago.sorchat.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Identity(val id: String, val name: String, val token: String)

/** Small key-value storage for the account; contacts and messages live in [ChatDatabase]. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("sorchat", Context.MODE_PRIVATE)

    var identity: Identity?
        get() = prefs.getString(KEY_IDENTITY, null)?.let { Json.decodeFromString(it) }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_IDENTITY) else putString(KEY_IDENTITY, Json.encodeToString(value))
        }

    /** Whether this account has a passkey, so it can be recovered on another device. */
    var hasPasskey: Boolean
        get() = prefs.getBoolean(KEY_HAS_PASSKEY, false)
        set(value) = prefs.edit { putBoolean(KEY_HAS_PASSKEY, value) }

    /** This install's current FCM token, as last reported by FCM. */
    var pushToken: String?
        get() = prefs.getString(KEY_CURRENT_PUSH_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_CURRENT_PUSH_TOKEN, value) }

    /** The FCM token last registered with the server, so it's only sent again when it changes. */
    var registeredPushToken: String?
        get() = prefs.getString(KEY_PUSH_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_PUSH_TOKEN, value) }

    private companion object {
        const val KEY_IDENTITY = "identity"
        const val KEY_HAS_PASSKEY = "has_passkey"
        const val KEY_CURRENT_PUSH_TOKEN = "push_token"
        const val KEY_PUSH_TOKEN = "registered_push_token"
    }
}
