package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A coarse luma map of one frame: the mean luma of every [cellPx] square. Comparing two maps finds where the screen
 * changed while ignoring compression noise and sub-cell flicker; it is cheap enough to run on every recorded frame.
 */
internal class MotionSignature(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val cellPx: Int,
    val columns: Int,
    val rows: Int,
    private val luma: IntArray,
) {
    /**
     * Cells whose mean luma moved by more than [threshold]; fewer than [minCells] changed cells count as no change
     * (a blinking caret, a clock digit). The bounds are in source pixels of the analyzed frame.
     */
    fun compare(
        other: MotionSignature,
        threshold: Int = LUMA_THRESHOLD,
        minCells: Int = MIN_CHANGED_CELLS,
    ): MotionChange {
        if (other.columns != columns || other.rows != rows || other.cellPx != cellPx) {
            return MotionChange(CaptureRegion(0, 0, sourceWidthPx, sourceHeightPx), 1.0)
        }
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        var changed = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val index = row * columns + column
                if (abs(luma[index] - other.luma[index]) > threshold) {
                    changed++
                    left = min(left, column)
                    top = min(top, row)
                    right = max(right, column)
                    bottom = max(bottom, row)
                }
            }
        }
        if (changed < minCells) return MotionChange(null, 0.0)
        val x = left * cellPx
        val y = top * cellPx
        val bounds = CaptureRegion(
            x,
            y,
            min((right + 1) * cellPx, sourceWidthPx) - x,
            min((bottom + 1) * cellPx, sourceHeightPx) - y,
        )
        return MotionChange(bounds, changed.toDouble() / (columns * rows))
    }

    companion object {
        /** Mean luma difference of one cell that counts as a change, out of 255. */
        const val LUMA_THRESHOLD: Int = 6

        /** Changed cells below this count are treated as noise. */
        const val MIN_CHANGED_CELLS: Int = 3

        /** Default cell side in source pixels. */
        const val CELL_PX: Int = 8
    }
}

/** Where two frames differ: the bounding box of the changed cells, `null` when nothing relevant changed. */
internal data class MotionChange(val bounds: CaptureRegion?, val changedFraction: Double) {
    val isChanged: Boolean get() = bounds != null
}

/** The coarse luma map of this frame; cells at the right and bottom edges may be partial. */
internal fun PixelGrid.motionSignature(cellPx: Int = MotionSignature.CELL_PX): MotionSignature {
    require(cellPx > 0) { "Motion cell must be positive: $cellPx" }
    val columns = (widthPx + cellPx - 1) / cellPx
    val rows = (heightPx + cellPx - 1) / cellPx
    val sums = LongArray(columns * rows)
    val counts = IntArray(columns * rows)
    for (y in 0 until heightPx) {
        val row = y / cellPx
        for (x in 0 until widthPx) {
            val index = row * columns + x / cellPx
            sums[index] += luma(argb[y * widthPx + x]).toLong()
            counts[index]++
        }
    }
    val luma = IntArray(sums.size) { (sums[it] / counts[it]).toInt() }
    return MotionSignature(widthPx, heightPx, cellPx, columns, rows, luma)
}

/** Rec. 601 luma in integer arithmetic; the motion map only compares, so rounding does not matter. */
private fun luma(pixel: Int): Int {
    val red = pixel ushr RED_SHIFT and CHANNEL_MASK
    val green = pixel ushr GREEN_SHIFT and CHANNEL_MASK
    val blue = pixel and CHANNEL_MASK
    return (red * LUMA_RED + green * LUMA_GREEN + blue * LUMA_BLUE) / LUMA_SCALE
}

private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MASK = 0xFF
private const val LUMA_RED = 299
private const val LUMA_GREEN = 587
private const val LUMA_BLUE = 114
private const val LUMA_SCALE = 1000

/** The smallest rectangle that holds both, or the non-null one. */
internal fun CaptureRegion?.union(other: CaptureRegion?): CaptureRegion? {
    if (this == null) return other
    if (other == null) return this
    val left = min(x, other.x)
    val top = min(y, other.y)
    return CaptureRegion(left, top, max(right, other.right) - left, max(bottom, other.bottom) - top)
}

/**
 * The ROI a recording is framed to: the [changed] area grown by a margin and to a readable minimum size, kept inside
 * [area]. Without a change, or when the change covers most of [area], the whole [area] is returned.
 */
internal fun autoRoi(
    changed: CaptureRegion?,
    area: CaptureRegion,
    minSizePx: Int = AUTO_ROI_MIN_PX,
    fullFraction: Double = AUTO_ROI_FULL_FRACTION,
): CaptureRegion {
    if (changed == null) return area
    val marginX = max(AUTO_ROI_MARGIN_PX, (changed.widthPx * AUTO_ROI_MARGIN_FRACTION).toInt())
    val marginY = max(AUTO_ROI_MARGIN_PX, (changed.heightPx * AUTO_ROI_MARGIN_FRACTION).toInt())
    val width = max(changed.widthPx + marginX * 2, minSizePx).coerceAtMost(area.widthPx)
    val height = max(changed.heightPx + marginY * 2, minSizePx).coerceAtMost(area.heightPx)
    val centerX = changed.x + changed.widthPx / 2
    val centerY = changed.y + changed.heightPx / 2
    val x = (centerX - width / 2).coerceIn(area.x, area.right - width)
    val y = (centerY - height / 2).coerceIn(area.y, area.bottom - height)
    val framed = CaptureRegion(x, y, width, height)
    return if (framed.areaPx >= area.areaPx * fullFraction) area else framed
}

private const val AUTO_ROI_MIN_PX = 256
private const val AUTO_ROI_MARGIN_PX = 24
private const val AUTO_ROI_MARGIN_FRACTION = 0.1
private const val AUTO_ROI_FULL_FRACTION = 0.7
