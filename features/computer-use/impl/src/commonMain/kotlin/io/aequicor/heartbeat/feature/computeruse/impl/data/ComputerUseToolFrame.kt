package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolImage
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.TileGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/** Reads only the acknowledged frame and includes its bytes before capture cleanup can remove the file. */
internal suspend fun FrameStore.agentToolFrame(output: ComputerUseOutput.FrameReady): AgentToolResult {
    val content = read(output.capture.path)
    if (content == null || content.isEmpty()) return AgentToolResult("CaptureFailed", isError = true)
    val mimeType = when (output.capture.format) {
        CaptureFormat.Png -> "image/png"
        CaptureFormat.Jpeg -> "image/jpeg"
    }
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
    put("regionX", reference.region.x)
    put("regionY", reference.region.y)
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
    put("hint", "The image is included in this response; use computer_zoom for a native-resolution crop.")
}.toString()
