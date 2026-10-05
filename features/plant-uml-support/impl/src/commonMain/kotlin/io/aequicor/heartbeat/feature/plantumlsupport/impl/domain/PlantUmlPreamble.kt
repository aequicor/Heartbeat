package io.aequicor.heartbeat.feature.plantumlsupport.impl.domain

import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlStyle
import kotlin.math.roundToInt

/** PlantUML's reference resolution: one pixel per logical unit. */
internal const val PLANTUML_BASE_DPI = 96
private const val MIN_FONT_SIZE = 8
private const val MAX_FONT_SIZE = 32
private const val RGB_MASK = 0xFFFFFF
private const val OPAQUE_ALPHA = 0xFF
private const val ALPHA_SHIFT = 24
private const val HEX_RGB_DIGITS = 6
private const val HEX_ALPHA_DIGITS = 2
private const val HEX_RADIX = 16

/**
 * Config lines PlantUML inserts after the diagram's start directive (they keep the line numbers of the author's
 * source): the in-process Smetana layout instead of an external Graphviz, the resolution for [scale] and a theme
 * from [style] on a transparent background. Statements in the diagram itself come later and override the theme.
 * Lines contain only numbers and colors — never text from the source.
 */
internal fun plantUmlPreamble(style: PlantUmlStyle, scale: Float): List<String> {
    val dpi = (PLANTUML_BASE_DPI * scale).roundToInt()
    val fontSize = style.fontSize.roundToInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    val text = style.text.hex()
    val line = style.line.hex()
    val fill = style.fill.hex()
    val border = style.border.hex()
    return listOf(
        "!pragma layout smetana",
        "skinparam dpi $dpi",
        "<style>",
        "root {",
        "  FontName SansSerif",
        "  FontSize $fontSize",
        "  FontColor $text",
        "  LineColor $line",
        "  BackGroundColor $fill",
        "}",
        "element {",
        "  FontColor $text",
        "  LineColor $border",
        "  BackGroundColor $fill",
        "}",
        "note {",
        "  FontColor $text",
        "  LineColor $border",
        "  BackGroundColor ${style.accentFill.hex()}",
        "}",
        "stereotype {",
        "  FontColor ${style.secondaryText.hex()}",
        "}",
        "document {",
        "  BackGroundColor transparent",
        "}",
        "</style>",
    )
}

/** `#RRGGBB`, or `#RRGGBBAA` for a translucent ARGB color. */
private fun Int.hex(): String {
    val rgb = (this and RGB_MASK).toString(HEX_RADIX).padStart(HEX_RGB_DIGITS, '0')
    val alpha = (this ushr ALPHA_SHIFT) and OPAQUE_ALPHA
    return if (alpha == OPAQUE_ALPHA) "#$rgb" else "#$rgb${alpha.toString(HEX_RADIX).padStart(HEX_ALPHA_DIGITS, '0')}"
}
