package io.aequicor.heartbeat.feature.plantumlsupport.impl.data

import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlLink

/** Bounds shared by CMap extraction and the worker protocol, independent of the image byte budget. */
internal const val MAX_DIAGRAM_LINKS = 512
internal const val MAX_LINK_TEXT_BYTES = 16 * 1024
internal const val MAX_LINK_METADATA_BYTES = 256 * 1024

/**
 * Reads only the rectangular areas emitted by the bundled PlantUML PNG exporter. This is deliberately not an XML
 * document parser: PlantUML writes raw ampersands in href, while escaping its tooltip. No markup is executed.
 * CMap coordinates include the PNG export scale, so they are divided by that same scale exactly once.
 */
internal fun plantUmlLinks(cmap: String, scale: Float, width: Float, height: Float): List<PlantUmlLink> {
    if (cmap.length > MAX_LINK_METADATA_BYTES || !scale.isFinite() || scale <= 0f) return emptyList()
    var bytes = 0
    return Area.findAll(cmap).mapNotNull { match ->
        val values = match.groupValues
        val coords = values[CMAP_COORDINATES_GROUP].split(',').map { it.toFloatOrNull()?.div(scale) ?: Float.NaN }
        if (coords.size != RECTANGLE_COORDINATES) {
            null
        } else {
            PlantUmlLink(
                coords[0],
                coords[1],
                coords[2],
                coords[RECTANGLE_COORDINATES - 1],
                values[1],
                decodeTooltip(values[2]),
            )
                .takeIf { it.isValid(width, height) }
        }
    }.take(MAX_DIAGRAM_LINKS).takeWhile {
        bytes += it.metadataBytes()
        bytes <= MAX_LINK_METADATA_BYTES
    }.toList()
}

internal fun PlantUmlLink.isValid(width: Float, height: Float): Boolean =
    listOf(left, top, right, bottom, width, height).all { it.isFinite() } &&
        left >= 0f && top >= 0f && right > left && bottom > top && right <= width && bottom <= height &&
        destination.isNotBlank() && destination.none { it.isISOControl() } &&
        destination.encodeToByteArray().size <= MAX_LINK_TEXT_BYTES &&
        tooltip.encodeToByteArray().size <= MAX_LINK_TEXT_BYTES

internal fun PlantUmlLink.metadataBytes(): Int =
    destination.encodeToByteArray().size + tooltip.encodeToByteArray().size + RECTANGLE_COORDINATES * Float.SIZE_BYTES

private fun decodeTooltip(text: String): String = text.replace("&#34;", "\"").replace("&#39;", "'")
    .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&#38;", "&").replace("&amp;", "&")

private val Area = Regex(
    """<area shape="rect" id="[^"]*" href="([^"]*)" title="([^"]*)" alt="" coords="([0-9., -]+)"/>""",
)

private const val RECTANGLE_COORDINATES = 4

private const val CMAP_COORDINATES_GROUP = 3
