package io.aequicor.heartbeat.ds.components

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.InputStream

@Composable
internal actual fun platformAttachmentInput(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
    onImage: (ByteArray) -> Unit,
): Modifier = Modifier

@Composable
internal actual fun rememberClipboardImageReader(): () -> ByteArray? {
    val context = LocalContext.current
    return remember(context) { { readClipboardImage(context) } }
}

private fun readClipboardImage(context: Context): ByteArray? = try {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val uri = clipboard.primaryClip?.getItemAt(0)?.uri
    if (uri == null || context.contentResolver.getType(uri)?.startsWith("image/") != true) {
        null
    } else {
        context.contentResolver.openInputStream(uri)?.use { it.readBounded()?.asClipboardPng() }
    }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Log.tag("DS/AttachmentInput").w(error) { "Clipboard image read failed" }
    null
}

private fun InputStream.readBounded(): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    var count = read(buffer)
    while (count >= 0 && total <= MAX_CLIPBOARD_BYTES) {
        output.write(buffer, 0, count)
        total += count
        count = read(buffer)
    }
    return output.toByteArray().takeIf { total <= MAX_CLIPBOARD_BYTES }
}

private fun ByteArray.asClipboardPng(): ByteArray? {
    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(this, 0, size, dimensions)
    require(
        dimensions.outWidth.toLong() * dimensions.outHeight <= MAX_CLIPBOARD_PIXELS,
    ) { "Clipboard image exceeds pixel limit" }
    val bitmap = BitmapFactory.decodeByteArray(this, 0, size) ?: return null
    return try {
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
            output.toByteArray().takeIf { it.size <= MAX_CLIPBOARD_BYTES }
        }
    } finally {
        bitmap.recycle()
    }
}

private const val MAX_CLIPBOARD_BYTES = 10 * 1024 * 1024
private const val MAX_CLIPBOARD_PIXELS = 25L * 1024 * 1024

private const val PNG_QUALITY = 100
