package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One frame in memory as packed `0xAARRGGBB` pixels, row by row from the top-left corner.
 *
 * Every reduction the host applies before encoding — cropping, scaling, grayscale, quantization, sharpening —
 * is a pure function of this buffer, so it is testable without a screen, a codec or a platform.
 */
internal class PixelGrid(val widthPx: Int, val heightPx: Int, val argb: IntArray) {
    init {
        require(widthPx > 0 && heightPx > 0) { "PixelGrid must have a positive size: ${widthPx}x$heightPx" }
        require(argb.size == widthPx * heightPx) {
            "PixelGrid buffer must hold ${widthPx * heightPx} pixels, got ${argb.size}"
        }
    }

    /** The pixel at `x,y` as `0xAARRGGBB`. */
    fun pixel(x: Int, y: Int): Int = argb[y * widthPx + x]

    /** A copy of [region]; the region must lie inside this grid. */
    fun region(region: CaptureRegion): PixelGrid {
        require(region.x + region.widthPx <= widthPx && region.y + region.heightPx <= heightPx) {
            "Region $region leaves the ${widthPx}x$heightPx frame"
        }
        val target = IntArray(region.widthPx * region.heightPx)
        for (row in 0 until region.heightPx) {
            val sourceStart = (region.y + row) * widthPx + region.x
            argb.copyInto(target, row * region.widthPx, sourceStart, sourceStart + region.widthPx)
        }
        return PixelGrid(region.widthPx, region.heightPx, target)
    }

    /** A bilinear reduction or enlargement to `targetWidthPx x targetHeightPx`. */
    fun scaled(targetWidthPx: Int, targetHeightPx: Int): PixelGrid {
        if (targetWidthPx == widthPx && targetHeightPx == heightPx) return this
        require(targetWidthPx > 0 && targetHeightPx > 0) { "Scaled size must be positive" }
        val target = IntArray(targetWidthPx * targetHeightPx)
        val stepX = widthPx.toDouble() / targetWidthPx
        val stepY = heightPx.toDouble() / targetHeightPx
        for (y in 0 until targetHeightPx) {
            val sourceY = ((y + HALF) * stepY - HALF).coerceAtLeast(0.0)
            val y0 = sourceY.toInt().coerceIn(0, heightPx - 1)
            val y1 = (y0 + 1).coerceAtMost(heightPx - 1)
            val weightY = sourceY - y0
            for (x in 0 until targetWidthPx) {
                val sourceX = ((x + HALF) * stepX - HALF).coerceAtLeast(0.0)
                val x0 = sourceX.toInt().coerceIn(0, widthPx - 1)
                val x1 = (x0 + 1).coerceAtMost(widthPx - 1)
                val weightX = sourceX - x0
                target[y * targetWidthPx + x] = bilinear(x0, y0, x1, y1, weightX, weightY)
            }
        }
        return PixelGrid(targetWidthPx, targetHeightPx, target)
    }

    /** Luma of every pixel, written back into all three channels. */
    fun grayscale(): PixelGrid = map { pixel ->
        val gray = luma(pixel)
        pack(OPAQUE, gray, gray, gray)
    }

    /** Every channel snapped to the nearest of [levels] steps. */
    fun quantized(levels: Int): PixelGrid {
        require(levels in 2..MAX_LEVELS) { "Quantization levels must be in 2..$MAX_LEVELS: $levels" }
        val step = CHANNEL_RANGE.toDouble() / (levels - 1)
        return map { pixel ->
            pack(
                alpha = pixel ushr ALPHA_SHIFT and CHANNEL_MASK,
                red = snap(pixel ushr RED_SHIFT and CHANNEL_MASK, step),
                green = snap(pixel ushr GREEN_SHIFT and CHANNEL_MASK, step),
                blue = snap(pixel and CHANNEL_MASK, step),
            )
        }
    }

    /** A 3x3 convolution that restores edge contrast lost by scaling; it adds no information. */
    fun sharpened(): PixelGrid {
        val target = IntArray(argb.size)
        for (y in 0 until heightPx) {
            for (x in 0 until widthPx) {
                target[y * widthPx + x] = convolve(x, y)
            }
        }
        return PixelGrid(widthPx, heightPx, target)
    }

    /** Floyd–Steinberg error diffusion of the luma into [levels] gray steps. */
    fun dithered(levels: Int): PixelGrid {
        require(levels in 2..MAX_LEVELS) { "Dither levels must be in 2..$MAX_LEVELS: $levels" }
        val step = CHANNEL_RANGE.toDouble() / (levels - 1)
        val error = DoubleArray(widthPx * heightPx)
        val target = IntArray(argb.size)
        for (y in 0 until heightPx) ditherRow(y, step, error, target)
        return PixelGrid(widthPx, heightPx, target)
    }

    /** Quantizes one row of [dithered] and hands the rounding error to [spread]. */
    private fun ditherRow(y: Int, step: Double, error: DoubleArray, target: IntArray) {
        for (x in 0 until widthPx) {
            val index = y * widthPx + x
            val original = luma(argb[index]).toDouble() + error[index]
            val quantized = snap(original.roundToInt().coerceIn(0, CHANNEL_RANGE), step)
            target[index] = pack(OPAQUE, quantized, quantized, quantized)
            spread(index, x, y, original - quantized, error)
        }
    }

    /** Floyd–Steinberg weights: 7/16 right, 3/16 below-left, 5/16 below, 1/16 below-right. */
    private fun spread(index: Int, x: Int, y: Int, residual: Double, error: DoubleArray) {
        if (x + 1 < widthPx) error[index + 1] += residual * SEVEN_SIXTEENTHS
        if (y + 1 >= heightPx) return
        if (x > 0) error[index + widthPx - 1] += residual * THREE_SIXTEENTHS
        error[index + widthPx] += residual * FIVE_SIXTEENTHS
        if (x + 1 < widthPx) error[index + widthPx + 1] += residual * ONE_SIXTEENTH
    }

    private fun map(transform: (Int) -> Int): PixelGrid {
        val target = IntArray(argb.size)
        for (index in argb.indices) target[index] = transform(argb[index])
        return PixelGrid(widthPx, heightPx, target)
    }

    private fun bilinear(x0: Int, y0: Int, x1: Int, y1: Int, weightX: Double, weightY: Double): Int {
        val top = blend(pixel(x0, y0), pixel(x1, y0), weightX)
        val bottom = blend(pixel(x0, y1), pixel(x1, y1), weightX)
        return blend(top, bottom, weightY)
    }

    private fun blend(first: Int, second: Int, weight: Double): Int {
        val keep = 1.0 - weight
        return pack(
            alpha = channel(first, ALPHA_SHIFT, keep) + channel(second, ALPHA_SHIFT, weight),
            red = channel(first, RED_SHIFT, keep) + channel(second, RED_SHIFT, weight),
            green = channel(first, GREEN_SHIFT, keep) + channel(second, GREEN_SHIFT, weight),
            blue = channel(first, 0, keep) + channel(second, 0, weight),
        )
    }

    private fun channel(pixel: Int, shift: Int, weight: Double): Int =
        ((pixel ushr shift and CHANNEL_MASK) * weight).roundToInt().coerceIn(0, CHANNEL_RANGE)

    private fun convolve(x: Int, y: Int): Int {
        val center = pixel(x, y)
        val sum = IntArray(CHANNELS)
        accumulate(sum, center, CENTER_WEIGHT)
        accumulate(sum, sample(x - 1, y), EDGE_WEIGHT)
        accumulate(sum, sample(x + 1, y), EDGE_WEIGHT)
        accumulate(sum, sample(x, y - 1), EDGE_WEIGHT)
        accumulate(sum, sample(x, y + 1), EDGE_WEIGHT)
        return pack(
            alpha = center ushr ALPHA_SHIFT and CHANNEL_MASK,
            red = sum[0],
            green = sum[1],
            blue = sum[2],
        )
    }

    private fun accumulate(sum: IntArray, pixel: Int, weight: Int) {
        sum[0] += (pixel ushr RED_SHIFT and CHANNEL_MASK) * weight
        sum[1] += (pixel ushr GREEN_SHIFT and CHANNEL_MASK) * weight
        sum[2] += (pixel and CHANNEL_MASK) * weight
    }

    /** Out-of-frame neighbours repeat the edge pixel, so edges stay instead of darkening. */
    private fun sample(x: Int, y: Int): Int = pixel(x.coerceIn(0, widthPx - 1), y.coerceIn(0, heightPx - 1))

    private companion object {
        const val ALPHA_SHIFT = 24
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val CHANNEL_MASK = 0xFF
        const val CHANNEL_RANGE = 255
        const val MAX_LEVELS = 256
        const val OPAQUE = 255
        const val HALF = 0.5
        const val CENTER_WEIGHT = 5
        const val EDGE_WEIGHT = -1
        const val CHANNELS = 3
        const val SEVEN_SIXTEENTHS = 7.0 / 16.0
        const val FIVE_SIXTEENTHS = 5.0 / 16.0
        const val THREE_SIXTEENTHS = 3.0 / 16.0
        const val ONE_SIXTEENTH = 1.0 / 16.0
        const val LUMA_RED = 0.299
        const val LUMA_GREEN = 0.587
        const val LUMA_BLUE = 0.114

        fun luma(pixel: Int): Int {
            val red = pixel ushr RED_SHIFT and CHANNEL_MASK
            val green = pixel ushr GREEN_SHIFT and CHANNEL_MASK
            val blue = pixel and CHANNEL_MASK
            return (red * LUMA_RED + green * LUMA_GREEN + blue * LUMA_BLUE).roundToInt().coerceIn(0, CHANNEL_RANGE)
        }

        fun snap(value: Int, step: Double): Int {
            val levels = (value / step).roundToInt()
            return (levels * step).roundToInt().coerceIn(0, CHANNEL_RANGE)
        }

        fun pack(alpha: Int, red: Int, green: Int, blue: Int): Int =
            (alpha.coerceIn(0, CHANNEL_RANGE) shl ALPHA_SHIFT) or
                (red.coerceIn(0, CHANNEL_RANGE) shl RED_SHIFT) or
                (green.coerceIn(0, CHANNEL_RANGE) shl GREEN_SHIFT) or
                blue.coerceIn(0, CHANNEL_RANGE)
    }
}

/** A single-color frame; used by tests and by hosts that can only report a placeholder. */
internal fun solidGrid(widthPx: Int, heightPx: Int, argb: Int): PixelGrid =
    PixelGrid(widthPx, heightPx, IntArray(widthPx * heightPx) { argb })

/** Absolute difference between two frames in luma; a cheap "did anything change" check. */
internal fun PixelGrid.delta(other: PixelGrid): Int {
    if (other.widthPx != widthPx || other.heightPx != heightPx) return Int.MAX_VALUE
    var changed = 0
    for (index in argb.indices) {
        if (abs(argb[index] - other.argb[index]) > 0) changed++
    }
    return changed
}
