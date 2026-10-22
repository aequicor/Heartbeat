package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun hbPlainTextClipEntry(text: String): ClipEntry = ClipEntry.withPlainText(text)

/** Browser clipboard items are read asynchronously only; the entry passes through unchanged. */
internal actual fun hbClipEntryPlainText(entry: ClipEntry): String? = null
