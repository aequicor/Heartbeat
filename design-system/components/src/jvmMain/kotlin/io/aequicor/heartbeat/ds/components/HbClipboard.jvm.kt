package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun hbPlainTextClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))
