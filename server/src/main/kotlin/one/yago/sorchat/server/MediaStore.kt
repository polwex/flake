package one.yago.sorchat.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/** Uploads larger than this are rejected. */
const val MAX_MEDIA_BYTES = 16L * 1024 * 1024

/**
 * Uploaded files (voice notes for now), kept on disk until the message referencing them is
 * acked. Ids are 128 random bits, so knowing an id is what grants access to the file.
 */
class MediaStore(private val dir: File, private val store: Store) {
    private val random = SecureRandom()

    init {
        dir.mkdirs()
    }

    /** Saves an upload. Returns its id, or null if it exceeds [MAX_MEDIA_BYTES]. */
    suspend fun save(owner: String, mimeType: String, input: InputStream): String? {
        val id = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val file = File(dir, id)
        val size = withContext(Dispatchers.IO) { file.outputStream().use { input.copyAtMost(it, MAX_MEDIA_BYTES) } }
        if (size == null) {
            file.delete()
            return null
        }
        store.addMedia(Media(id, owner, mimeType, size))
        return id
    }

    suspend fun find(id: String): Media? = if (ID_PATTERN.matches(id)) store.findMedia(id) else null

    fun file(media: Media) = File(dir, media.id)

    suspend fun delete(id: String) {
        store.deleteMedia(id)
        withContext(Dispatchers.IO) { File(dir, id).delete() }
    }

    suspend fun deleteOlderThan(cutoff: Long) {
        for (id in store.mediaOlderThan(cutoff)) delete(id)
    }

    private companion object {
        /** Also keeps request paths from escaping [dir]. */
        val ID_PATTERN = Regex("[0-9a-f]{32}")
    }
}

/** Copies up to [limit] bytes. Returns the number copied, or null if the input was longer. */
private fun InputStream.copyAtMost(out: OutputStream, limit: Long): Long? {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) return total
        total += n
        if (total > limit) return null
        out.write(buffer, 0, n)
    }
}
