package one.yago.sorchat.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** Keeps a small server from filling up: files are only relayed, so they shouldn't pile up anyway. */
data class MediaLimits(
    val maxFileBytes: Long = 25L * 1024 * 1024,
    /** Total size of all files waiting to be downloaded. */
    val quotaBytes: Long = 1024L * 1024 * 1024,
    /** Files nobody downloaded are deleted after this. */
    val ttl: Duration = 14.days,
)

sealed interface SaveResult {
    data class Saved(val id: String) : SaveResult
    data object TooLarge : SaveResult
    data object QuotaFull : SaveResult
}

/**
 * Uploaded files (photos, files, voice notes), kept on disk only until the recipient has
 * downloaded them (or [MediaLimits.ttl] passes). Only the uploader and the recipient of the
 * message referencing a file may download it.
 */
class MediaStore(private val dir: File, private val store: Store, val limits: MediaLimits = MediaLimits()) {
    private val random = SecureRandom()

    init {
        dir.mkdirs()
    }

    /** Saves an upload, within the size limit and the quota. [declaredSize] is Content-Length, if sent. */
    suspend fun save(owner: String, mimeType: String, input: InputStream, declaredSize: Long?): SaveResult {
        val room = limits.quotaBytes - store.mediaBytes()
        if (declaredSize != null && declaredSize > limits.maxFileBytes) return SaveResult.TooLarge
        if (room <= 0 || (declaredSize != null && declaredSize > room)) return SaveResult.QuotaFull
        val limit = minOf(limits.maxFileBytes, room)

        val id = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val file = File(dir, id)
        val size = withContext(Dispatchers.IO) { file.outputStream().use { input.copyAtMost(it, limit) } }
        if (size == null) {
            file.delete()
            return if (limit < limits.maxFileBytes) SaveResult.QuotaFull else SaveResult.TooLarge
        }
        store.addMedia(Media(id, owner, mimeType, size))
        return SaveResult.Saved(id)
    }

    suspend fun find(id: String): Media? = if (ID_PATTERN.matches(id)) store.findMedia(id) else null

    fun file(media: Media) = File(dir, media.id)

    suspend fun delete(id: String) {
        store.deleteMedia(id)
        withContext(Dispatchers.IO) { File(dir, id).delete() }
    }

    suspend fun deleteExpired() {
        for (id in store.mediaOlderThan(System.currentTimeMillis() - limits.ttl.inWholeMilliseconds)) delete(id)
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

