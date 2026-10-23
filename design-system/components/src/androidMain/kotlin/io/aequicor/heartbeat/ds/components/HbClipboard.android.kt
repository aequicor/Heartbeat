package io.aequicor.heartbeat.ds.components

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun hbPlainTextClipEntry(text: String): ClipEntry = ClipEntry(ClipData.newPlainText(null, text))

internal actual fun hbClipEntryPlainText(entry: ClipEntry): String? =
    entry.clipData.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
