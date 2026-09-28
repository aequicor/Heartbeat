package io.aequicor.heartbeat.ds.theme

import androidx.compose.ui.text.font.FontFamily

/** Native font selection and actual renderer identity; no font files are bundled or copied. */
internal data class HbHostFont(
    val family: FontFamily,
    val requestedFamily: String? = null,
    val actualFamily: String? = null,
) {
    val isFallback: Boolean get() = requestedFamily != null && requestedFamily != actualFamily
}

/** The native interface family for interface text; code keeps its separate monospace family. */
internal expect fun hostFontResolution(): HbHostFont
