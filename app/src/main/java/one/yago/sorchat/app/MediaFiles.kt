package one.yago.sorchat.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** A file ready to send, already copied into the app's own storage. */
class PreparedMedia(val file: File, val mimeType: String, val name: String?, val width: Int? = null, val height: Int? = null)

/** Largest file we try to send; the server enforces its own (configurable) limit too. */
const val MAX_FILE_BYTES = 25L * 1024 * 1024

/** Photos are scaled so their longer side is at most this, like other messengers do. */
private const val MAX_PHOTO_EDGE = 1600
private const val PHOTO_QUALITY = 82

/** Decodes a photo (applying its rotation), shrinks it and saves it as a JPEG in [dir]. */
suspend fun preparePhoto(resolver: ContentResolver, uri: Uri, dir: File): PreparedMedia = withContext(Dispatchers.IO) {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val (w, h) = info.size.width to info.size.height
        val scale = MAX_PHOTO_EDGE.toFloat() / max(w, h)
        if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
        // A software bitmap, so it can be compressed.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val file = File(dir.apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it) }
    PreparedMedia(file, "image/jpeg", null, bitmap.width, bitmap.height).also { bitmap.recycle() }
}

/** Copies any picked file into [dir], keeping its name and type. Throws if it's too big to send. */
suspend fun prepareFile(resolver: ContentResolver, uri: Uri, dir: File): PreparedMedia = withContext(Dispatchers.IO) {
    var name: String? = null
    var size: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            name = c.getString(0)
            size = if (c.isNull(1)) null else c.getLong(1)
        }
    }
    if ((size ?: 0) > MAX_FILE_BYTES) throw IllegalArgumentException("Files can be up to ${MAX_FILE_BYTES shr 20} MB")
    val mimeType = resolver.getType(uri)
        ?: name?.substringAfterLast('.', "")?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
        ?: "application/octet-stream"
    val extension = name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 8 }?.let { ".$it" } ?: ""
    val file = File(dir.apply { mkdirs() }, "${UUID.randomUUID()}$extension")
    checkNotNull(resolver.openInputStream(uri)) { "Couldn't read the file" }.use { input ->
        file.outputStream().use { input.copyTo(it) }
    }
    if (file.length() > MAX_FILE_BYTES) {
        file.delete()
        throw IllegalArgumentException("Files can be up to ${MAX_FILE_BYTES shr 20} MB")
    }
    PreparedMedia(file, mimeType, name ?: "file$extension")
}

/** Where a downloaded attachment goes, keeping the original extension so other apps can open it. */
fun downloadTarget(dir: File, message: ChatMessage): File {
    val fromName = message.fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 8 }
    val extension = fromName ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(message.mimeType ?: "") ?: "bin"
    return File(dir.apply { mkdirs() }, "${UUID.randomUUID()}.$extension")
}
