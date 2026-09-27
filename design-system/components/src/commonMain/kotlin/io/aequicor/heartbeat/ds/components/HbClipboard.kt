package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.platform.ClipEntry

/** Wraps unchanged plain text for [androidx.compose.ui.platform.Clipboard.setClipEntry]. */
internal expect fun hbPlainTextClipEntry(text: String): ClipEntry
