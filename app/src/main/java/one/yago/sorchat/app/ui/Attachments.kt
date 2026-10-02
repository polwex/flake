package one.yago.sorchat.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import one.yago.sorchat.app.ChatMessage
import one.yago.sorchat.app.formatFileSize
import one.yago.sorchat.app.ui.theme.LocalGradients
import one.yago.sorchat.app.ui.theme.Sunset
import java.io.File

/** Bottom sheet with the ways to attach something: gallery, camera, any file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(onDismiss: () -> Unit, onPhoto: (Uri) -> Unit, onFile: (Uri) -> Unit) {
    val context = LocalContext.current
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onPhoto)
        onDismiss()
    }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) captureUri?.let(onPhoto)
        onDismiss()
    }
    fun launchCamera() {
        val file = File(context.cacheDir, "camera/capture-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        captureUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file).also(takePhoto::launch)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else onDismiss()
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onFile)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AttachOption(Icons.Rounded.Image, "Photo") { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            AttachOption(Icons.Rounded.PhotoCamera, "Camera") {
                // The app declares CAMERA (for video calls), so taking a picture needs it granted too.
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }
            AttachOption(Icons.AutoMirrored.Rounded.InsertDriveFile, "File") { pickFile.launch(arrayOf("*/*")) }
        }
    }
}

@Composable
private fun AttachOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIconButton(icon, label, onClick, size = 64.dp, background = LocalGradients.current.sunset)
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    }
}

/** A photo in a chat bubble, sized from its dimensions so the layout doesn't jump while it loads. */
@Composable
fun PhotoContent(message: ChatMessage, progress: Float?, onOpen: () -> Unit, onDownload: () -> Unit) {
    val ratio = if (message.width != null && message.height != null && message.width > 0) message.height.toFloat() / message.width else 0.75f
    val width = 240.dp
    val height = (width * ratio).coerceIn(140.dp, 320.dp)
    Box(
        Modifier
            .size(width, height)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { if (message.localPath != null) onOpen() else onDownload() },
        contentAlignment = Alignment.Center,
    ) {
        val path = message.localPath
        when {
            path != null -> AsyncImage(
                model = Uri.fromFile(File(path)),
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            message.unavailable -> Text("Photo no longer available", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> TransferBadge(progress, onDownload, size = message.fileSize)
        }
        // Uploading: a ring over the photo.
        if (path != null && progress != null) {
            Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { progress }, color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
            }
        }
    }
}

/** A file in a chat bubble: type icon, name, size, and its download state. */
@Composable
fun FileContent(message: ChatMessage, mine: Boolean, contentColor: Color, progress: Float?, onDownload: () -> Unit) {
    val context = LocalContext.current
    val path = message.localPath
    Row(
        Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                when {
                    path != null -> openFile(context, File(path), message.mimeType)
                    !message.unavailable -> onDownload()
                }
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (mine) solid(Color.White) else LocalGradients.current.sunset),
            contentAlignment = Alignment.Center,
        ) {
            when {
                progress != null -> CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(32.dp),
                    color = if (mine) Sunset.Coral else Color.White,
                    strokeWidth = 3.dp,
                )
                path == null && !mine && !message.unavailable ->
                    Icon(Icons.Rounded.Download, contentDescription = "Download", tint = if (mine) Sunset.Coral else Color.White)
                else -> Icon(fileIcon(message.mimeType), contentDescription = null, tint = if (mine) Sunset.Coral else Color.White)
            }
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                message.fileName ?: "File",
                color = contentColor,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                message.fileSize?.let(::formatFileSize),
                message.fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase(),
                if (message.unavailable) "no longer available" else null,
            ).joinToString(" · ")
            Text(details, color = contentColor.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Waiting/downloading indicator for an attachment that isn't on this device yet. */
@Composable
fun TransferBadge(progress: Float?, onDownload: () -> Unit, size: Long? = null, tint: Color = MaterialTheme.colorScheme.primary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (progress != null) {
            CircularProgressIndicator(progress = { progress }, color = tint)
        } else {
            IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, contentDescription = "Download", tint = tint) }
        }
        size?.let { Text(formatFileSize(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Full-screen photo: pinch to zoom, drag to pan, save to the gallery. */
@Composable
fun ImageViewer(path: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
        ) {
            AsyncImage(
                model = Uri.fromFile(File(path)),
                contentDescription = "Photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
            Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                RoundIconButton(Icons.Rounded.Close, "Close", onClose, size = 48.dp)
                RoundIconButton(Icons.Rounded.SaveAlt, "Save to gallery", { saveToGallery(context, File(path)) }, size = 48.dp)
            }
        }
    }
}

private fun fileIcon(mimeType: String?): ImageVector = when {
    mimeType == null -> Icons.AutoMirrored.Rounded.InsertDriveFile
    mimeType == "application/pdf" -> Icons.Rounded.PictureAsPdf
    mimeType.startsWith("image/") -> Icons.Rounded.Image
    mimeType.startsWith("video/") -> Icons.Rounded.Movie
    mimeType.startsWith("audio/") -> Icons.Rounded.AudioFile
    mimeType.startsWith("text/") || "document" in mimeType -> Icons.Rounded.Description
    "zip" in mimeType || "compressed" in mimeType || "tar" in mimeType -> Icons.Rounded.FolderZip
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** Opens a received or sent file in whichever app handles its type. */
fun openFile(context: Context, file: File, mimeType: String?) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, null))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app here can open this file", Toast.LENGTH_SHORT).show()
    }
}

/** Copies a photo into Pictures/sorchat, where gallery apps find it. */
fun saveToGallery(context: Context, file: File) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "sorchat_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/sorchat")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        Toast.makeText(context, "Couldn't save the photo", Toast.LENGTH_SHORT).show()
        return
    }
    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    Toast.makeText(context, "Saved to Pictures/sorchat", Toast.LENGTH_SHORT).show()
}

