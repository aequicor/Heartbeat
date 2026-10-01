package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureColorModel
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CapturePresets
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.EncodedFrame
import io.aequicor.heartbeat.feature.computeruse.api.VisionBudget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.EncodingLadder
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameEncoder
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** One encoded frame together with the reference that addresses it on disk. */
internal data class ProducedFrame(val reference: CaptureRef, val encoded: EncodedFrame)

/**
 * Turns raw pixels into stored artifacts: a lossless master frame and the reduced frames handed to callers.
 *
 * Reduction is a fixed ladder ([EncodingLadder]) instead of a lucky retry, and every artifact is written under
 * the application storage root. Only sizes, formats and identifiers are logged — never a path or pixel data.
 */
internal class FramePipeline(
    private val encoder: FrameEncoder,
    private val store: FrameStore,
    private val budget: VisionBudget,
    private val dispatchers: DispatcherProvider,
) {
    private val log = Log.tag("FramePipeline")

    /** Decodes an engine-provided master through the platform codec. */
    suspend fun decode(content: ByteArray): PixelGrid? = withContext(dispatchers.io) { encoder.decode(content) }

    /** Stores [source] losslessly as the master frame of [session]; crops are served from it later. */
    suspend fun storeMaster(
        session: CaptureSessionId,
        id: CaptureId,
        source: PixelGrid,
        region: CaptureRegion,
        sequence: Long,
    ): ProducedFrame? = produce(
        session = session,
        id = id,
        source = source,
        region = region,
        masterWidthPx = source.widthPx,
        masterHeightPx = source.heightPx,
        encoding = CapturePresets.Master,
        sequence = sequence,
        isMaster = true,
        applyBudget = false,
    )

    /** Stores one reduced frame derived from a master frame. */
    // A stored derivative carries its session identity, both geometries and encoding atomically.
    @Suppress("LongParameterList")
    suspend fun derive(
        session: CaptureSessionId,
        id: CaptureId,
        source: PixelGrid,
        region: CaptureRegion,
        masterWidthPx: Int,
        masterHeightPx: Int,
        encoding: CaptureEncoding,
        sequence: Long,
    ): ProducedFrame? = produce(
        session = session,
        id = id,
        source = source,
        region = region,
        masterWidthPx = masterWidthPx,
        masterHeightPx = masterHeightPx,
        encoding = encoding,
        sequence = sequence,
        isMaster = false,
        applyBudget = true,
    )

    // Common implementation of master and derivative persistence needs their complete artifact metadata.
    @Suppress("LongParameterList")
    private suspend fun produce(
        session: CaptureSessionId,
        id: CaptureId,
        source: PixelGrid,
        region: CaptureRegion,
        masterWidthPx: Int,
        masterHeightPx: Int,
        encoding: CaptureEncoding,
        sequence: Long,
        isMaster: Boolean,
        applyBudget: Boolean,
    ): ProducedFrame? = withContext(dispatchers.io) {
        val candidates = if (isMaster) listOf(encoding) else candidates(source, encoding, applyBudget)
        val produced = candidates.firstNotNullOfOrNull { candidate ->
            val encoded = encode(prepare(source, candidate, applyBudget), candidate)
            if (encoded != null && fitsLimit(encoded, candidate)) {
                persisted(
                    session = session,
                    id = id,
                    region = region,
                    masterWidthPx = masterWidthPx,
                    masterHeightPx = masterHeightPx,
                    encoded = encoded,
                    sequence = sequence,
                    isMaster = isMaster,
                )
            } else {
                null
            }
        }
        if (produced == null) {
            log.w { "frame rejected after ${candidates.size} attempts id=$id limit=${encoding.maxBytes}" }
        }
        produced
    }

    /** `true` when the encoded frame respects the byte limit; an oversized attempt is logged, not thrown. */
    private fun fitsLimit(encoded: EncodedFrame, candidate: CaptureEncoding): Boolean {
        val isWithinLimit = candidate.maxBytes <= 0 || encoded.content.size <= candidate.maxBytes
        if (!isWithinLimit) log.d { "frame over budget format=${encoded.format} bytes=${encoded.content.size}" }
        return isWithinLimit
    }

    /** Writes one encoded frame and describes it with a reference that never carries pixel data. */
    // The reference is constructed from the complete encoded artifact metadata.
    @Suppress("LongParameterList")
    private suspend fun persisted(
        session: CaptureSessionId,
        id: CaptureId,
        region: CaptureRegion,
        masterWidthPx: Int,
        masterHeightPx: Int,
        encoded: EncodedFrame,
        sequence: Long,
        isMaster: Boolean,
    ): ProducedFrame {
        val path = store.write(session, id, encoded)
        val reference = CaptureRef(
            id = id,
            session = session,
            format = encoded.format,
            widthPx = encoded.widthPx,
            heightPx = encoded.heightPx,
            region = region,
            masterWidthPx = masterWidthPx,
            masterHeightPx = masterHeightPx,
            bytes = encoded.content.size.toLong(),
            estimatedTokens = budget.cost.tokens(encoded.widthPx, encoded.heightPx),
            path = path,
            sequence = sequence,
            isMaster = isMaster,
        )
        log.i {
            "frame stored id=$id master=$isMaster format=${encoded.format} " +
                "size=${encoded.widthPx}x${encoded.heightPx} bytes=${encoded.content.size}"
        }
        return ProducedFrame(reference, encoded)
    }

    private fun candidates(source: PixelGrid, encoding: CaptureEncoding, applyBudget: Boolean): List<CaptureEncoding> {
        val ladder = EncodingLadder.candidates(encoding, source.widthPx, source.heightPx)
        if (!applyBudget) return ladder
        val fitted = budget.dimensionsFor(source.widthPx, source.heightPx)
        return ladder.map { candidate ->
            if (candidate.maxWidthPx in 1 until fitted.widthPx) {
                candidate
            } else {
                candidate.copy(maxWidthPx = fitted.widthPx, maxHeightPx = fitted.heightPx)
            }
        }
    }

    private suspend fun encode(grid: PixelGrid, encoding: CaptureEncoding): EncodedFrame? = try {
        encoder.encode(grid, encoding)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "frame encoding failed format=${encoding.format}" }
        null
    }

    private fun prepare(source: PixelGrid, encoding: CaptureEncoding, applyBudget: Boolean): PixelGrid {
        var grid = source
        val target = targetSize(source, encoding, applyBudget)
        if (target.first != grid.widthPx || target.second != grid.heightPx) {
            grid = grid.scaled(target.first, target.second)
        }
        grid = when (encoding.colorModel) {
            CaptureColorModel.Rgb -> grid

            CaptureColorModel.Gray8 -> if (encoding.isDithered) grid.dithered(GRAY_LEVELS) else grid.grayscale()

            CaptureColorModel.Indexed ->
                if (encoding.isDithered) grid.dithered(INDEXED_LEVELS) else grid.quantized(INDEXED_LEVELS)
        }
        return if (encoding.isSharpened) grid.sharpened() else grid
    }

    private fun targetSize(source: PixelGrid, encoding: CaptureEncoding, applyBudget: Boolean): Pair<Int, Int> {
        val limited = limitSize(source.widthPx, source.heightPx, encoding.maxWidthPx, encoding.maxHeightPx)
        if (!applyBudget) return limited
        val fitted = budget.dimensionsFor(limited.first, limited.second)
        return minOf(limited.first, fitted.widthPx) to minOf(limited.second, fitted.heightPx)
    }

    private fun limitSize(widthPx: Int, heightPx: Int, maxWidthPx: Int, maxHeightPx: Int): Pair<Int, Int> {
        if (maxWidthPx <= 0 && maxHeightPx <= 0) return widthPx to heightPx
        val byWidth = if (maxWidthPx <= 0) Double.MAX_VALUE else maxWidthPx.toDouble() / widthPx
        val byHeight = if (maxHeightPx <= 0) Double.MAX_VALUE else maxHeightPx.toDouble() / heightPx
        val factor = minOf(1.0, byWidth, byHeight)
        return (widthPx * factor).toInt().coerceAtLeast(1) to (heightPx * factor).toInt().coerceAtLeast(1)
    }

    private companion object {
        const val GRAY_LEVELS = 256
        const val INDEXED_LEVELS = 32
    }
}
