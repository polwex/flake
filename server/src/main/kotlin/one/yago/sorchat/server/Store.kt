package one.yago.sorchat.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import one.yago.sorchat.protocol.RegisterResponse
import one.yago.sorchat.protocol.ServerFrame
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64

data class User(val id: String, val name: String)

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
        val token = ByteArray(32).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
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

    suspend fun findUserByToken(token: String): User? = db {
        prepareStatement("SELECT id, name FROM users WHERE token_hash = ?").use {
            it.setString(1, hash(token))
            it.executeQuery().use { rs -> if (rs.next()) User(rs.getString(1), rs.getString(2)) else null }
        }
    }

    suspend fun storeMessage(message: ServerFrame.Message, recipient: String): StoreResult = db {
        val inserted = prepareStatement(
            "INSERT OR IGNORE INTO messages(id, sender, recipient, sender_name, body, sent_at) VALUES (?, ?, ?, ?, ?, ?)"
        ).use {
            it.setString(1, message.id)
            it.setString(2, message.from)
            it.setString(3, recipient)
            it.setString(4, message.fromName)
            it.setString(5, message.body)
            it.setLong(6, message.sentAt)
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
            "SELECT id, sender, sender_name, body, sent_at FROM messages WHERE recipient = ? ORDER BY sent_at"
        ).use {
            it.setString(1, recipient)
            it.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(ServerFrame.Message(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)))
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

    private fun newUserId(): String = buildString {
        repeat(8) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** Lowercase letters and digits without look-alikes (0/o, 1/l/i), so ids are easy to type. */
        private const val ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

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
            }
            return Store(conn)
        }
    }
}
