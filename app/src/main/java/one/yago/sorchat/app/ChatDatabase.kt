package one.yago.sorchat.app

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "contacts")
data class Contact(@PrimaryKey val id: String, val name: String)

enum class MessageStatus { SENDING, SENT, FAILED, RECEIVED }

@Entity(tableName = "messages", indices = [Index("peer", "sentAt")])
data class ChatMessage(
    @PrimaryKey val id: String,
    /** The contact this conversation is with, whichever side sent the message. */
    val peer: String,
    val fromMe: Boolean,
    val body: String,
    val sentAt: Long,
    val status: MessageStatus,
    /** Voice notes: the audio file on this device. Null for text, or if the download was no longer available. */
    val localPath: String? = null,
    /** The server's id for the attachment, once uploaded (outgoing) or as received (incoming). */
    val mediaId: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    /** Files: the original name. */
    val fileName: String? = null,
    /** Attachments: size in bytes (known before downloading). */
    val fileSize: Long? = null,
    /** Images: pixel size, for laying out the bubble before the image is there. */
    val width: Int? = null,
    val height: Int? = null,
    /** The server no longer has the attachment (expired before it was downloaded). */
    @ColumnInfo(defaultValue = "0") val unavailable: Boolean = false,
)

val ChatMessage.isVoice: Boolean get() = mimeType?.startsWith("audio/") == true
val ChatMessage.isImage: Boolean get() = mimeType?.startsWith("image/") == true
val ChatMessage.isFile: Boolean get() = mimeType != null && !isVoice && !isImage

/** Has an attachment that isn't on this device (yet). */
val ChatMessage.needsDownload: Boolean get() = mediaId != null && localPath == null && !fromMe && !unavailable

/** One-line summary for the contact list and notifications. */
fun ChatMessage.preview(): String = when {
    isVoice -> "🎤 Voice message (${formatDuration(durationMs ?: 0)})"
    isImage -> if (body.isNotBlank()) "📷 $body" else "📷 Photo"
    isFile -> "📄 ${fileName ?: "File"}"
    else -> body
}

/** "2.3 MB", "640 KB" */
fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

fun formatDuration(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

@Dao
interface ChatDao {
    @Query("SELECT * FROM contacts ORDER BY rowid")
    fun contacts(): Flow<List<Contact>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContact(contact: Contact)

    @Query("SELECT * FROM messages WHERE peer = :peer ORDER BY sentAt")
    fun conversation(peer: String): Flow<List<ChatMessage>>

    /** The newest message of each conversation, for the contact list preview. */
    @Query("SELECT * FROM messages m WHERE sentAt = (SELECT MAX(sentAt) FROM messages WHERE peer = m.peer) GROUP BY peer")
    fun lastMessages(): Flow<List<ChatMessage>>

    /** Returns -1 if a message with this id already exists (a redelivery). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: MessageStatus)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: String): ChatMessage?

    @Query("UPDATE messages SET mediaId = :mediaId WHERE id = :id")
    suspend fun setMediaId(id: String, mediaId: String)

    @Query("UPDATE messages SET localPath = :path WHERE id = :id")
    suspend fun setLocalPath(id: String, path: String)

    @Query("UPDATE messages SET unavailable = 1 WHERE id = :id")
    suspend fun setUnavailable(id: String)

    @Query("SELECT * FROM messages WHERE status = 'SENDING' ORDER BY sentAt")
    suspend fun pending(): List<ChatMessage>
}

@Database(
    entities = [Contact::class, ChatMessage::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao

    companion object {
        @Volatile
        private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, "sorchat.db")
                .build()
                .also { instance = it }
        }
    }
}
