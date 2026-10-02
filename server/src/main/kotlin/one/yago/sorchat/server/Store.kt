package one.yago.sorchat.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import one.yago.sorchat.protocol.Attachment
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.RegisterResponse
import one.yago.sorchat.protocol.ServerFrame
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.Base64

data class User(val id: String, val name: String)

data class Media(val id: String, val owner: String, val mimeType: String, val size: Long, val recipient: String? = null)

/** A registered passkey. Binary fields are base64url-encoded. */
data class Passkey(val credentialId: String, val userId: String, val publicKeyCose: String, val signCount: Long)

enum class StoreResult { STORED, DUPLICATE, CONFLICT }

/**
 * SQLite-backed storage for users and the queue of undelivered messages.
 * A single connection guarded by a lock is plenty at this scale.
 */
class Store private constructor(private val conn: Connection) {
    private val lock = Any()
    private val random = SecureRandom()

    private suspend fun <T> db(block: Connection.() -> T): T =
        withContext(Dispatchers.IO) { synchronized(lock) { conn.block() } }

    suspend fun createUser(name: String): RegisterResponse = db {
        val token = newToken()
        var id: String
        do id = newUserId() while (!insertUser(id, name, hash(token)))
        RegisterResponse(id, token)
    }

    /** Returns false if [id] is already taken. */
    private fun Connection.insertUser(id: String, name: String, tokenHash: String): Boolean =
        prepareStatement("INSERT OR IGNORE INTO users(id, name, token_hash, created_at) VALUES (?, ?, ?, ?)").use {
            it.setString(1, id)
            it.setString(2, name)
            it.setString(3, tokenHash)
            it.setLong(4, System.currentTimeMillis())
            it.executeUpdate() == 1
        }

    suspend fun findUser(id: String): User? = db {
        prepareStatement("SELECT id, name FROM users WHERE id = ?").use {
            it.setString(1, id)
            it.executeQuery().use { rs -> if (rs.next()) User(rs.getString(1), rs.getString(2)) else null }
        }
    }

    /** Replaces the user's token (logging out whichever device had the old one) and returns the new one. */
    suspend fun issueToken(userId: String): String = db {
        val token = newToken()
        prepareStatement("UPDATE users SET token_hash = ? WHERE id = ?").use {
            it.setString(1, hash(token))
            it.setString(2, userId)
            check(it.executeUpdate() == 1) { "No user $userId" }
        }
        token
    }

    suspend fun addPasskey(passkey: Passkey) = db {
        prepareStatement("INSERT INTO passkeys(credential_id, user_id, public_key_cose, sign_count, created_at) VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, passkey.credentialId)
            it.setString(2, passkey.userId)
            it.setString(3, passkey.publicKeyCose)
            it.setLong(4, passkey.signCount)
            it.setLong(5, System.currentTimeMillis())
            it.executeUpdate()
        }
    }

    suspend fun passkey(credentialId: String): Passkey? = db {
        prepareStatement("SELECT credential_id, user_id, public_key_cose, sign_count FROM passkeys WHERE credential_id = ?").use {
            it.setString(1, credentialId)
            it.executeQuery().use { rs -> if (rs.next()) Passkey(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) else null }
        }
    }

    suspend fun passkeysFor(userId: String): List<Passkey> = db {
        prepareStatement("SELECT credential_id, user_id, public_key_cose, sign_count FROM passkeys WHERE user_id = ?").use {
            it.setString(1, userId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(Passkey(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))) } }
        }
    }

    suspend fun updatePasskeySignCount(credentialId: String, signCount: Long) = db {
        prepareStatement("UPDATE passkeys SET sign_count = ? WHERE credential_id = ?").use {
            it.setLong(1, signCount)
            it.setString(2, credentialId)
            it.executeUpdate()
        }
    }

    suspend fun findUserByToken(token: String): User? = db {
        prepareStatement("SELECT id, name FROM users WHERE token_hash = ?").use {
            it.setString(1, hash(token))
            it.executeQuery().use { rs -> if (rs.next()) User(rs.getString(1), rs.getString(2)) else null }
        }
    }

    suspend fun setPushToken(userId: String, token: String) = db {
        prepareStatement("UPDATE users SET push_token = ? WHERE id = ?").use {
            it.setString(1, token)
            it.setString(2, userId)
            it.executeUpdate()
        }
    }

    suspend fun pushToken(userId: String): String? = db {
        prepareStatement("SELECT push_token FROM users WHERE id = ?").use {
            it.setString(1, userId)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** Forgets a token FCM reported as invalid, unless the device has registered a new one since. */
    suspend fun clearPushToken(userId: String, token: String) = db {
        prepareStatement("UPDATE users SET push_token = NULL WHERE id = ? AND push_token = ?").use {
            it.setString(1, userId)
            it.setString(2, token)
            it.executeUpdate()
        }
    }

    suspend fun storeMessage(message: ServerFrame.Message, recipient: String): StoreResult = db {
        val inserted = prepareStatement(
            "INSERT OR IGNORE INTO messages(id, sender, recipient, sender_name, body, sent_at, attachment) VALUES (?, ?, ?, ?, ?, ?, ?)"
        ).use {
            it.setString(1, message.id)
            it.setString(2, message.from)
            it.setString(3, recipient)
            it.setString(4, message.fromName)
            it.setString(5, message.body)
            it.setLong(6, message.sentAt)
            it.setString(7, message.attachment?.let { a -> ProtocolJson.encodeToString(Attachment.serializer(), a) })
            it.executeUpdate() == 1
        }
        if (inserted) return@db StoreResult.STORED
        // Either the sender retried (fine) or the id belongs to someone else's message.
        val existingSender = prepareStatement("SELECT sender FROM messages WHERE id = ?").use {
            it.setString(1, message.id)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        // A missing row means it was already delivered and acked: also a retry.
        if (existingSender == null || existingSender == message.from) StoreResult.DUPLICATE else StoreResult.CONFLICT
    }

    suspend fun pendingFor(recipient: String): List<ServerFrame.Message> = db {
        prepareStatement(
            "SELECT id, sender, sender_name, body, sent_at, attachment FROM messages WHERE recipient = ? ORDER BY sent_at"
        ).use {
            it.setString(1, recipient)
            it.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val attachment = rs.getString(6)?.let { ProtocolJson.decodeFromString(Attachment.serializer(), it) }
                        add(ServerFrame.Message(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), attachment))
                    }
                }
            }
        }
    }

    suspend fun deleteMessage(id: String, recipient: String) = db {
        prepareStatement("DELETE FROM messages WHERE id = ? AND recipient = ?").use {
            it.setString(1, id)
            it.setString(2, recipient)
            it.executeUpdate()
        }
    }

    suspend fun deleteMessagesOlderThan(cutoff: Long) = db {
        prepareStatement("DELETE FROM messages WHERE sent_at < ?").use {
            it.setLong(1, cutoff)
            it.executeUpdate()
        }
    }

    suspend fun addMedia(media: Media) = db {
        prepareStatement("INSERT INTO media(id, owner, mime_type, size, created_at) VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, media.id)
            it.setString(2, media.owner)
            it.setString(3, media.mimeType)
            it.setLong(4, media.size)
            it.setLong(5, System.currentTimeMillis())
            it.executeUpdate()
        }
    }

    suspend fun findMedia(id: String): Media? = db {
        prepareStatement("SELECT id, owner, mime_type, size, recipient FROM media WHERE id = ?").use {
            it.setString(1, id)
            it.executeQuery().use { rs ->
                if (rs.next()) Media(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)) else null
            }
        }
    }

    /** Records who the file was sent to: they may download (and then delete) it. */
    suspend fun setMediaRecipient(id: String, recipient: String) = db {
        prepareStatement("UPDATE media SET recipient = ? WHERE id = ?").use {
            it.setString(1, recipient)
            it.setString(2, id)
            it.executeUpdate()
        }
    }

    /** Total size of the files waiting on the server. */
    suspend fun mediaBytes(): Long = db {
        createStatement().use { it.executeQuery("SELECT COALESCE(SUM(size), 0) FROM media").use { rs -> rs.next(); rs.getLong(1) } }
    }

    suspend fun deleteMedia(id: String) = db {
        prepareStatement("DELETE FROM media WHERE id = ?").use {
            it.setString(1, id)
            it.executeUpdate()
        }
    }

    suspend fun mediaOlderThan(cutoff: Long): List<String> = db {
        prepareStatement("SELECT id FROM media WHERE created_at < ?").use {
            it.setLong(1, cutoff)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    private fun newToken(): String = ByteArray(32).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private fun newUserId(): String = buildString {
        repeat(8) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** Lowercase letters and digits without look-alikes (0/o, 1/l/i), so ids are easy to type. */
        private const val ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        private fun columns(st: Statement, table: String): Set<String> =
            st.executeQuery("PRAGMA table_info($table)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }

        /** Additive schema changes for databases created by older versions. */
        private fun migrate(st: Statement) {
            if ("push_token" !in columns(st, "users")) st.execute("ALTER TABLE users ADD COLUMN push_token TEXT")
            if ("attachment" !in columns(st, "messages")) st.execute("ALTER TABLE messages ADD COLUMN attachment TEXT")
            if ("recipient" !in columns(st, "media")) st.execute("ALTER TABLE media ADD COLUMN recipient TEXT")
        }

        fun open(path: String): Store {
            if (path != ":memory:") File(path).absoluteFile.parentFile.mkdirs()
            val conn = DriverManager.getConnection("jdbc:sqlite:$path")
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode = WAL")
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        token_hash TEXT NOT NULL UNIQUE,
                        created_at INTEGER NOT NULL
                    )
                    """
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS messages (
                        id TEXT PRIMARY KEY,
                        sender TEXT NOT NULL,
                        recipient TEXT NOT NULL,
                        sender_name TEXT NOT NULL,
                        body TEXT NOT NULL,
                        sent_at INTEGER NOT NULL
                    )
                    """
                )
                st.execute("CREATE INDEX IF NOT EXISTS messages_recipient ON messages(recipient, sent_at)")
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS passkeys (
                        credential_id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        public_key_cose TEXT NOT NULL,
                        sign_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """
                )
                st.execute("CREATE INDEX IF NOT EXISTS passkeys_user ON passkeys(user_id)")
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS media (
                        id TEXT PRIMARY KEY,
                        owner TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """
                )
                migrate(st)
            }
            return Store(conn)
        }
    }
}
