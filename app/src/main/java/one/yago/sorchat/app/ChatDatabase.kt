package one.yago.sorchat.app

import android.content.Context
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
)

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

    @Query("SELECT * FROM messages WHERE status = 'SENDING' ORDER BY sentAt")
    suspend fun pending(): List<ChatMessage>
}

@Database(entities = [Contact::class, ChatMessage::class], version = 1)
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
