package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.CaptureColorModel
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat

/**
 * The reduction ladder applied when an encoded frame does not fit [CaptureEncoding.maxBytes].
 *
 * The order is fixed and pure, so a caller can promise a size without retrying at random: a lossy frame first
 * loses quality, a lossless frame first loses color depth, and only then both lose resolution. Every candidate
 * keeps the requested byte limit, and the last one is the smallest reduction the host is willing to send.
 */
internal object EncodingLadder {
    /** Candidate encodings, best first; the first entry is always [encoding] itself. */
    fun candidates(encoding: CaptureEncoding, widthPx: Int, heightPx: Int): List<CaptureEncoding> {
        if (encoding.maxBytes <= 0) return listOf(encoding)
        val candidates = LinkedHashSet<CaptureEncoding>()
        candidates += encoding
        candidates += cheaperColor(encoding)
        candidates += cheapestColor(encoding)
        val smallest = cheapestColor(encoding).first()
        candidates += smallest.copy(
            maxWidthPx = reduced(widthPx, MEDIUM_FACTOR),
            maxHeightPx = reduced(heightPx, MEDIUM_FACTOR),
        )
        candidates += smallest.copy(
            maxWidthPx = reduced(widthPx, SMALL_FACTOR),
            maxHeightPx = reduced(heightPx, SMALL_FACTOR),
        )
        return candidates.toList()
    }

    private fun cheaperColor(encoding: CaptureEncoding): List<CaptureEncoding> =
        if (encoding.format == CaptureFormat.Jpeg) {
            listOf(encoding.copy(quality = (encoding.quality - QUALITY_STEP).coerceAtLeast(MIN_QUALITY)))
        } else {
            listOf(encoding.copy(colorModel = CaptureColorModel.Gray8, isDithered = false))
        }

    private fun cheapestColor(encoding: CaptureEncoding): List<CaptureEncoding> =
        if (encoding.format == CaptureFormat.Jpeg) {
            listOf(encoding.copy(quality = MIN_QUALITY))
        } else {
            listOf(encoding.copy(colorModel = CaptureColorModel.Indexed, isDithered = true))
        }

    private fun reduced(sizePx: Int, factor: Double): Int = (sizePx * factor).toInt().coerceAtLeast(MIN_SIDE_PX)

    private const val QUALITY_STEP = 20
    private const val MIN_QUALITY = 30
    private const val MIN_SIDE_PX = 64
    private const val MEDIUM_FACTOR = 0.75
    private const val SMALL_FACTOR = 0.5
}
