package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import io.aequicor.heartbeat.core.logging.Log
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

private val log = Log.tag("HbClipboard")

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun hbPlainTextClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun hbClipEntryPlainText(entry: ClipEntry): String? {
    val transferable = entry.asAwtTransferable ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    return try {
        transferable.getTransferData(DataFlavor.stringFlavor) as? String
    } catch (e: UnsupportedFlavorException) {
        log.w(e) { "clipboard entry has no plain text" }
        null
    } catch (e: IOException) {
        log.w(e) { "clipboard entry text unavailable" }
        null
    }
}
