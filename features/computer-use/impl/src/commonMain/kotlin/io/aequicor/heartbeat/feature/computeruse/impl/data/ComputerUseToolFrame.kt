package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolImage
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureClip
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.TileGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/** Reads only the acknowledged frame and includes its bytes before capture cleanup can remove the file. */
internal suspend fun FrameStore.agentToolFrame(output: ComputerUseOutput.FrameReady): AgentToolResult {
    val content = read(output.capture.path)
    if (content == null || content.isEmpty()) return AgentToolResult("CaptureFailed", isError = true)
    val mimeType = output.capture.format.mimeType()
    return AgentToolResult(
        frameText(output.capture, output.tiles, output.master),
        images = listOf(AgentToolImage(mimeType, Base64.encode(content))),
    )
}

private fun frameText(reference: CaptureRef, tiles: TileGrid?, master: CaptureRef?): String = buildJsonObject {
    put("captureId", reference.id.value)
    put("masterCaptureId", master?.id?.value)
    put("path", reference.path)
    put("format", reference.format.name.lowercase())
    put("widthPx", reference.widthPx)
    put("heightPx", reference.heightPx)
    put("bytes", reference.bytes)
    put("estimatedTokens", reference.estimatedTokens)
    put("masterWidthPx", reference.masterWidthPx)
    put("masterHeightPx", reference.masterHeightPx)
    put("previewScale", reference.previewScale)
    put(
        "roi",
        buildJsonObject {
            put("x", reference.roi.x)
            put("y", reference.roi.y)
            put("width", reference.roi.widthPx)
            put("height", reference.roi.heightPx)
        },
    )
    put("hasTiles", tiles != null)
    tiles?.let { grid ->
        put(
            "tiles",
            buildJsonObject {
                put("columns", grid.columns)
                put("rows", grid.rows)
                put("format", "column:row (zero-based), e.g. 0:0")
                put("tileWidthPx", grid.tileWidthPx)
                put("tileHeightPx", grid.tileHeightPx)
                put("overlapPx", grid.overlapPx)
            },
        )
    }
    put(
        "hint",
        "The image is included in this response and shows the roi of the master frame; frame pixels are relative " +
            "to it. Use the zoom tool of the active family for a native-resolution crop.",
    )
}.toString()

/** Reads every keyframe of the acknowledged clip and returns them in order, with their timing and the clip ROI. */
internal suspend fun FrameStore.agentToolClip(clip: CaptureClip, prefix: String? = null): AgentToolResult {
    val images = clip.frames.map { frame ->
        val content = read(frame.capture.path)
        if (content == null || content.isEmpty()) return AgentToolResult("CaptureFailed", isError = true)
        AgentToolImage(frame.capture.format.mimeType(), Base64.encode(content))
    }
    return AgentToolResult(clipText(clip, prefix), images = images)
}

private fun CaptureFormat.mimeType(): String = when (this) {
    CaptureFormat.Png -> "image/png"
    CaptureFormat.Jpeg -> "image/jpeg"
}

private fun clipText(clip: CaptureClip, prefix: String?): String = buildJsonObject {
    prefix?.let { put("applied", it) }
    put("masterCaptureId", clip.master.id.value)
    put("durationMs", clip.durationMillis)
    put("stable", clip.isStable)
    put("changed", clip.hasChanged)
    put("autoRoi", clip.isAutoRoi)
    put(
        "roi",
        buildJsonObject {
            put("x", clip.roi.x)
            put("y", clip.roi.y)
            put("width", clip.roi.widthPx)
            put("height", clip.roi.heightPx)
        },
    )
    put(
        "frames",
        buildJsonArray {
            clip.frames.forEach { frame ->
                add(
                    buildJsonObject {
                        put("captureId", frame.capture.id.value)
                        put("tMs", frame.offsetMillis)
                        put("changedFraction", frame.changedFraction)
                        put("widthPx", frame.capture.widthPx)
                        put("heightPx", frame.capture.heightPx)
                    },
                )
            }
        },
    )
    put(
        "hint",
        "The images are the keyframes in order and show the roi of the master frame; the last one is the current " +
            "screen: input coordinates and zoom refer to it, earlier keyframes cannot be zoomed.",
    )
}.toString()
