package io.aequicor.heartbeat.ds.theme

import androidx.compose.ui.text.font.FontFamily

/** Compose for web draws on a canvas without system fonts: the default family is what the host page loads. */
internal actual fun hostFontResolution(): HbHostFont = HbHostFont(FontFamily.Default)
