package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CapturePresets
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.FrameSpace
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.MouseButton
import io.aequicor.heartbeat.feature.computeruse.api.NormalizedRegion
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MAX_CLICKS
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MAX_TYPED_CHARS
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// Argument parsing of the hosted computer tools: pure conversions of model-supplied JSON, bounded and validated.

private val argumentLog = Log.tag("ComputerUseToolArguments")
private const val MAX_KEYS = 6
private const val MIN_QUALITY = 1
private const val MAX_QUALITY = 100

internal fun click(arguments: JsonObject): InputAction? {
    val point = point(arguments, "x", "y") ?: return null
    return InputAction.Click(
        point = point,
        button = button(arguments.text("button")),
        count = (arguments.int("count") ?: 1).coerceIn(1, MAX_CLICKS),
        space = space(arguments.text("space")),
    )
}

internal fun drag(arguments: JsonObject): InputAction? {
    val from = point(arguments, "x", "y") ?: return null
    val to = point(arguments, "toX", "toY") ?: return null
    return InputAction.Drag(from, to, button(arguments.text("button")), space(arguments.text("space")))
}

internal fun scroll(arguments: JsonObject): InputAction? {
    val at = point(arguments, "x", "y") ?: return null
    return InputAction.Scroll(
        point = at,
        deltaX = arguments.int("deltaX") ?: 0,
        deltaY = arguments.int("deltaY") ?: 0,
        space = space(arguments.text("space")),
    )
}

internal fun typed(arguments: JsonObject): InputAction? {
    val text = arguments.text("text") ?: return null
    if (text.length > MAX_TYPED_CHARS) return null
    return InputAction.Type(text)
}

internal fun keys(arguments: JsonObject): InputAction? {
    val names = arguments["keys"]?.jsonPrimitive?.content?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    if (names.isNullOrEmpty() || names.size > MAX_KEYS) return null
    return InputAction.Key(names)
}

private fun point(arguments: JsonObject, xName: String, yName: String): FramePoint? {
    val x = arguments.number(xName) ?: return null
    val y = arguments.number(yName) ?: return null
    return FramePoint(x, y)
}

internal fun region(arguments: JsonObject): CaptureRegion? {
    val x = arguments.int("regionX") ?: return null
    val y = arguments.int("regionY") ?: return null
    val width = arguments.int("regionWidth") ?: return null
    val height = arguments.int("regionHeight") ?: return null
    return try {
        CaptureRegion(x, y, width, height)
    } catch (e: IllegalArgumentException) {
        argumentLog.w(e) { "region refused" }
        null
    }
}

internal fun normalized(arguments: JsonObject): NormalizedRegion? {
    val x = arguments.number("nx") ?: return null
    val y = arguments.number("ny") ?: return null
    val width = arguments.number("nw") ?: return null
    val height = arguments.number("nh") ?: return null
    return try {
        NormalizedRegion(x, y, width, height)
    } catch (e: IllegalArgumentException) {
        argumentLog.w(e) { "normalized region refused" }
        null
    }
}

internal fun encoding(arguments: JsonObject, default: CaptureEncoding): CaptureEncoding {
    val preset = arguments.text("preset")?.let { CapturePresets.byName(it) } ?: default
    val format = format(arguments.text("format")) ?: preset.format
    val quality = (arguments.int("quality") ?: preset.quality).coerceIn(MIN_QUALITY, MAX_QUALITY)
    val maxWidth = (arguments.int("maxWidth") ?: preset.maxWidthPx).coerceAtLeast(0)
    val maxBytes = (arguments.int("maxBytes") ?: preset.maxBytes).coerceAtLeast(0)
    return preset.copy(
        format = format,
        quality = quality,
        maxWidthPx = maxWidth,
        maxHeightPx = if (maxWidth > 0) maxWidth else preset.maxHeightPx,
        maxBytes = maxBytes,
    )
}

private fun format(name: String?): CaptureFormat? = when (name?.lowercase()) {
    "png" -> CaptureFormat.Png
    "jpg", "jpeg" -> CaptureFormat.Jpeg
    null -> null
    else -> null
}

private fun button(name: String?): MouseButton = when (name?.lowercase()) {
    "right" -> MouseButton.Right
    "middle" -> MouseButton.Middle
    else -> MouseButton.Left
}

private fun space(name: String?): FrameSpace = when (name?.lowercase()) {
    "master" -> FrameSpace.Master
    "normalized" -> FrameSpace.Normalized
    "screen" -> FrameSpace.Screen
    else -> FrameSpace.Preview
}
