package one.yago.sorchat.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Identity(val id: String, val name: String, val token: String)

@Serializable
data class Contact(val id: String, val name: String)

/** Small key-value storage for the account and contact list. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("sorchat", Context.MODE_PRIVATE)

    var identity: Identity?
        get() = prefs.getString(KEY_IDENTITY, null)?.let { Json.decodeFromString(it) }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_IDENTITY) else putString(KEY_IDENTITY, Json.encodeToString(value))
        }

    var contacts: List<Contact>
        get() = prefs.getString(KEY_CONTACTS, null)?.let { Json.decodeFromString(it) } ?: emptyList()
        set(value) = prefs.edit { putString(KEY_CONTACTS, Json.encodeToString(value)) }

    private companion object {
        const val KEY_IDENTITY = "identity"
        const val KEY_CONTACTS = "contacts"
    }
}
