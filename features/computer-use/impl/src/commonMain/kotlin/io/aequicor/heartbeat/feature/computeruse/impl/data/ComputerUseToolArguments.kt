package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CapturePresets
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.FrameSpace
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.MouseButton
import io.aequicor.heartbeat.feature.computeruse.api.NormalizedRegion
import io.aequicor.heartbeat.feature.computeruse.api.RecordRequest
import io.aequicor.heartbeat.feature.computeruse.api.TileGrid
import io.aequicor.heartbeat.feature.computeruse.api.TileRef
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

/** The `roi` argument of a frame or ROI tool, mapped into master pixels. */
internal sealed interface RoiArgument {
    /** The call names no ROI. */
    data object Absent : RoiArgument

    /** The ROI is malformed, empty or cannot be mapped; the call is refused instead of widened to the full frame. */
    data object Invalid : RoiArgument

    /** A well-formed ROI in master pixels; whether it fits the master and the active ROI is checked by the host. */
    data class Master(val region: CaptureRegion) : RoiArgument
}

/**
 * Reads `roi: {x, y, width, height, space}`. Preview coordinates are pixels of [frame], the frame the caller last
 * received; normalized ones are fractions of the master; master ones pass through.
 */
internal fun roiArgument(arguments: JsonObject, frame: CaptureRef?): RoiArgument {
    if ("roi" !in arguments) return RoiArgument.Absent
    val roi = arguments["roi"] as? JsonObject ?: return RoiArgument.Invalid
    val rectangle = rectangle(roi) ?: return RoiArgument.Invalid
    return try {
        when (roi.text("space")?.lowercase() ?: "preview") {
            "master" -> RoiArgument.Master(rectangle.toRegion())
            "normalized" -> frame?.let { RoiArgument.Master(rectangle.toNormalized(it)) } ?: RoiArgument.Invalid
            "preview" -> frame?.let { RoiArgument.Master(it.toMaster(rectangle)) } ?: RoiArgument.Invalid
            else -> RoiArgument.Invalid
        }
    } catch (e: IllegalArgumentException) {
        argumentLog.w(e) { "roi refused" }
        RoiArgument.Invalid
    }
}

private data class Rectangle(val x: Double, val y: Double, val width: Double, val height: Double) {
    fun toRegion(): CaptureRegion = CaptureRegion(x.toInt(), y.toInt(), width.toInt(), height.toInt())

    fun toNormalized(frame: CaptureRef): CaptureRegion =
        NormalizedRegion(x, y, width, height).toRegion(frame.masterWidthPx, frame.masterHeightPx)
}

/** Finite numbers with a positive size; anything else is refused. */
private fun rectangle(roi: JsonObject): Rectangle? {
    val values = listOf("x", "y", "width", "height").map { roi.number(it) ?: return null }
    if (values.any { !it.isFinite() }) return null
    val rectangle = Rectangle(values[0], values[1], values[2], values[3])
    return rectangle.takeIf { it.width > 0.0 && it.height > 0.0 }
}

/** Maps a rectangle in this frame's pixels into master pixels through the ROI the frame shows. */
private fun CaptureRef.toMaster(rectangle: Rectangle): CaptureRegion {
    val scaleX = roi.widthPx.toDouble() / widthPx
    val scaleY = roi.heightPx.toDouble() / heightPx
    return CaptureRegion(
        roi.x + (rectangle.x * scaleX).toInt(),
        roi.y + (rectangle.y * scaleY).toInt(),
        (rectangle.width * scaleX).toInt().coerceAtLeast(1),
        (rectangle.height * scaleY).toInt().coerceAtLeast(1),
    )
}

/** `<family>_record` arguments within the request limits; `null` when a value is out of range. */
internal fun recordRequest(arguments: JsonObject): RecordRequest? = try {
    RecordRequest(
        fps = arguments.int("fps") ?: RecordRequest.DEFAULT_FPS,
        maxDurationMillis = arguments.int("durationMs")?.toLong() ?: RecordRequest.DEFAULT_DURATION_MILLIS,
        isUntilStable = arguments.flag("untilStable") ?: true,
        maxFrames = arguments.int("maxFrames") ?: RecordRequest.DEFAULT_FRAMES,
    )
} catch (e: IllegalArgumentException) {
    argumentLog.w(e) { "recording refused" }
    null
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

/** An explicit `roi` or a `tile` laid over the active ROI of [identifier]'s master; `null` area means none. */
internal fun areaArgument(
    arguments: JsonObject,
    state: ComputerUseState.Capturing,
    identifier: CaptureId,
): AreaArgument {
    val source = listOfNotNull(state.master, state.lastPreview, state.lastCrop).firstOrNull { it.id == identifier }
    val tile = arguments.text("tile")?.let { TileRef.parse(it) }
    if ("tile" in arguments && tile == null) return AreaArgument.Invalid
    val roi = roiArgument(arguments, state.lastPreview)
    return when {
        roi == RoiArgument.Invalid -> AreaArgument.Invalid

        tile != null && roi != RoiArgument.Absent -> AreaArgument.Invalid

        tile != null ->
            source?.let { tileRegion(tile, it, state.roi) }?.let { AreaArgument.Area(it) } ?: AreaArgument.Invalid

        roi is RoiArgument.Master -> AreaArgument.Area(roi.region)

        else -> AreaArgument.Area(null)
    }
}

private fun tileRegion(tile: TileRef, source: CaptureRef, activeRoi: CaptureRegion?): CaptureRegion? {
    val base = activeRoi?.takeIf { it.isInside(CaptureRegion(0, 0, source.masterWidthPx, source.masterHeightPx)) }
        ?: CaptureRegion(0, 0, source.masterWidthPx, source.masterHeightPx)
    return TileGrid.forMaster(base.widthPx, base.heightPx)
        .region(tile.column, tile.row, base.widthPx, base.heightPx)
        ?.let { CaptureRegion(base.x + it.x, base.y + it.y, it.widthPx, it.heightPx) }
}

/** A frame area argument: a `roi` or a `tile`; `Area(null)` means the active ROI. */
internal sealed interface AreaArgument {
    data object Invalid : AreaArgument

    data class Area(val region: CaptureRegion?) : AreaArgument
}
