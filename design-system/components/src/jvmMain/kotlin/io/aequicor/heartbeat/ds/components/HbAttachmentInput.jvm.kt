package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

private val inputLog = Log.tag("DS/AttachmentInput")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun platformAttachmentInput(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
    onImage: (ByteArray) -> Unit,
): Modifier {
    val files = rememberUpdatedState(onFiles)
    val images = rememberUpdatedState(onImage)
    val read = rememberClipboardImageReader()
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = try {
                val dropped = event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                val paths = dropped.orEmpty().asSequence().filterIsInstance<File>()
                    .filter(File::isFile).map(File::getAbsolutePath).toList()
                if (paths.isNotEmpty()) {
                    inputLog.i { "User dropped files count=${paths.size}" }
                    files.value(paths)
                }
                paths.isNotEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                inputLog.w(error) { "File drop failed" }
                false
            }
        }
    }
    if (!enabled) return Modifier
    return Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target = target,
    ).onPreviewKeyEvent { event ->
        val hasPasteModifier = event.isMetaPressed || event.isCtrlPressed
        if (event.key != Key.V || !hasPasteModifier || event.type != KeyEventType.KeyDown) {
            false
        } else {
            read()?.let {
                inputLog.i { "User pasted image" }
                images.value(it)
                true
            } ?: false
        }
    }
}

@Composable
internal actual fun rememberClipboardImageReader(): () -> ByteArray? = remember { { clipboardImage() } }

private fun clipboardImage(): ByteArray? = try {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
        null
    } else {
        val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image
        if (image == null || !image.hasBoundedSize()) {
            null
        } else {
            val bitmap = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
            val graphics = bitmap.createGraphics()
            try {
                graphics.drawImage(image, 0, 0, null)
            } finally {
                graphics.dispose()
            }
            val bytes = ByteArrayOutputStream().use { output ->
                ImageIO.write(
                    bitmap,
                    "png",
                    output,
                )
                output.toByteArray()
            }
            bytes.takeIf { it.size <= MAX_IMAGE_BYTES }
        }
    }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    inputLog.w(error) { "Clipboard image read failed" }
    null
}

private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
private const val MAX_IMAGE_PIXELS = 25L * 1024 * 1024

private fun java.awt.Image.hasBoundedSize(): Boolean = getWidth(null) > 0 && getHeight(null) > 0 &&
    getWidth(null).toLong() * getHeight(null) <= MAX_IMAGE_PIXELS
