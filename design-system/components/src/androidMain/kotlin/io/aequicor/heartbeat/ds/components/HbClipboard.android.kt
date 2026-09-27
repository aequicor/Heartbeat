package io.aequicor.heartbeat.ds.components

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun hbPlainTextClipEntry(text: String): ClipEntry = ClipEntry(ClipData.newPlainText(null, text))
