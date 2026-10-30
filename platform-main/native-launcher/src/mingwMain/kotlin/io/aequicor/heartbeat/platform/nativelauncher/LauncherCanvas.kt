@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_copy_surface
import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_create_surface
import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_resolve_region
import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_shimmer_band
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.HBITMAP
import platform.windows.HDC
import platform.windows.HGDIOBJ
import platform.windows.POINT
import platform.windows.RECT
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Reused supersampled back buffer. A frame reaches the window in one blit, without background erasure. */
internal class LauncherCanvas(private val density: Double = 1.0) : AutoCloseable {
    private var memory: HDC? = null
    private var bitmap: HBITMAP? = null
    private var originalBitmap: HGDIOBJ? = null
    private var output: HDC? = null
    private var outputBitmap: HBITMAP? = null
    private var originalOutputBitmap: HGDIOBJ? = null
    private var pixels: COpaquePointer? = null
    private var outputPixels: COpaquePointer? = null
    private val textLayers = mutableListOf<(HDC) -> Unit>()
    private val textClips = mutableListOf<TextClip>()
    private val coverage = mutableListOf<TextClip>()
    private var pixelWidth = 0
    private var pixelHeight = 0
    private var width = 0
    private var height = 0
    private var capacityWidth = 0
    private var capacityHeight = 0

    // HbHostFont Windows family and HbTypography.Desktop weights; enlarged loading copy is user-approved.
    private val body = createLauncherFont((16 * density).roundToInt(), platform.windows.FW_NORMAL)
    private val caption = createLauncherFont((14 * density).roundToInt(), platform.windows.FW_NORMAL)
    private val title = createLauncherFont((23 * density).roundToInt(), platform.windows.FW_SEMIBOLD)
    private val welcome = createLauncherFont((28 * density).roundToInt(), platform.windows.FW_MEDIUM)

    fun paint(target: HDC, width: Int, height: Int, draw: LauncherCanvas.() -> Unit) {
        if (width <= 0 || height <= 0) return
        if (width > capacityWidth || height > capacityHeight || memory == null) resize(target, width, height)
        pixelWidth = width
        pixelHeight = height
        this.width = (width / density).toInt().coerceAtLeast(1)
        this.height = (height / density).toInt().coerceAtLeast(1)
        val dc = checkNotNull(memory)
        platform.windows.SetMapMode(dc, platform.windows.MM_ANISOTROPIC)
        platform.windows.SetWindowExtEx(dc, this.width, this.height, null)
        platform.windows.SetViewportExtEx(dc, width * SCALE, height * SCALE, null)
        textLayers.clear()
        coverage.clear()
        draw()
        platform.windows.SetMapMode(dc, platform.windows.MM_TEXT)
        val destination = checkNotNull(output)
        val sourcePixels = checkNotNull(pixels)
        val destinationPixels = checkNotNull(outputPixels)
        hb_copy_surface(sourcePixels, destinationPixels, width, height, capacityWidth)
        coverage.forEach {
            hb_resolve_region(
                sourcePixels,
                destinationPixels,
                capacityWidth,
                it.left,
                it.top,
                it.right,
                it.bottom,
            )
        }
        textLayers.forEach { it(destination) }
        platform.windows.BitBlt(target, 0, 0, width, height, destination, 0, 0, platform.windows.SRCCOPY)
    }

    private fun resize(target: HDC, width: Int, height: Int) {
        releaseBuffer()
        var isAllocated = false
        try {
            allocateBuffer(target, width, height)
            isAllocated = true
        } finally {
            if (!isAllocated) releaseBuffer()
        }
    }

    private fun allocateBuffer(target: HDC, width: Int, height: Int) {
        // Retain allocation while shrinking; growing a few pixels must not reallocate a large 4x bitmap every frame.
        capacityWidth = ((width + BUFFER_STEP - 1) / BUFFER_STEP) * BUFFER_STEP
        capacityHeight = ((height + BUFFER_STEP - 1) / BUFFER_STEP) * BUFFER_STEP
        val dc = checkNotNull(platform.windows.CreateCompatibleDC(target)) { "Cannot allocate startup drawing context" }
        memory = dc
        bitmap = memScoped {
            val data = alloc<COpaquePointerVar>()
            checkNotNull(hb_create_surface(target, capacityWidth * SCALE, capacityHeight * SCALE, data.ptr)) {
                "Cannot allocate startup frame buffer"
            }.also { pixels = checkNotNull(data.value) }
        }
        originalBitmap = platform.windows.SelectObject(dc, bitmap)
        val finalDc = checkNotNull(platform.windows.CreateCompatibleDC(target))
        output = finalDc
        outputBitmap = memScoped {
            val data = alloc<COpaquePointerVar>()
            checkNotNull(hb_create_surface(target, capacityWidth, capacityHeight, data.ptr)).also {
                outputPixels = checkNotNull(data.value)
            }
        }
        originalOutputBitmap = platform.windows.SelectObject(finalDc, outputBitmap)
        this.width = width
        this.height = height
    }

    /** Reuses an already complete resize frame without clearing or re-rendering its content. */
    fun present(target: HDC, width: Int, height: Int): Boolean {
        val dc = output ?: return false
        if (width != pixelWidth || height != pixelHeight) return false
        return platform.windows.BitBlt(target, 0, 0, width, height, dc, 0, 0, platform.windows.SRCCOPY) != 0
    }

    fun fill(x: Int, y: Int, width: Int, height: Int, color: UInt, radius: Int = 0) = memScoped {
        val dc = checkNotNull(memory)
        val brush = platform.windows.CreateSolidBrush(color)
        if (radius == 0) {
            val rect = alloc<RECT>()
            rect.left = x
            rect.top = y
            rect.right = x + width
            rect.bottom = y + height
            platform.windows.FillRect(dc, rect.ptr, brush)
        } else {
            cover(x.toDouble(), y.toDouble(), (x + width).toDouble(), (y + height).toDouble())
            val oldBrush = platform.windows.SelectObject(dc, brush)
            val oldPen = platform.windows.SelectObject(dc, platform.windows.GetStockObject(platform.windows.NULL_PEN))
            platform.windows.RoundRect(dc, x, y, x + width, y + height, radius * 2, radius * 2)
            platform.windows.SelectObject(dc, oldPen)
            platform.windows.SelectObject(dc, oldBrush)
        }
        platform.windows.DeleteObject(brush)
    }

    /** A true gradient inside the rounded placeholder mask; the surrounding field is never overpainted. */
    fun shimmer(x: Int, y: Int, width: Int, height: Int, base: UInt, highlight: UInt, center: Int?) = memScoped {
        val radius = minOf(height / 2, 6)
        fill(x, y, width, height, base, radius)
        if (center == null) return@memScoped
        val dc = checkNotNull(memory)
        val saved = platform.windows.SaveDC(dc)
        try {
            platform.windows.BeginPath(dc)
            platform.windows.RoundRect(dc, x, y, x + width, y + height, radius * 2, radius * 2)
            platform.windows.EndPath(dc)
            platform.windows.SelectClipPath(dc, platform.windows.RGN_AND)
            check(hb_shimmer_band(dc, y, y + height, center, SHIMMER_HALF_WIDTH, base, highlight) != 0) {
                "Cannot render the native skeleton gradient"
            }
        } finally {
            platform.windows.RestoreDC(dc, saved)
        }
    }

    fun text(
        value: String,
        x: Int,
        y: Int,
        width: Int,
        color: UInt,
        size: Int = 13,
        alignment: Int = platform.windows.DT_CENTER,
        multiline: Boolean = false,
    ) {
        val clips = textClips.toList()
        textLayers += { dc ->
            memScoped {
                val saved = platform.windows.SaveDC(dc)
                clips.forEach {
                    platform.windows.IntersectClipRect(
                        dc,
                        pixel(it.left),
                        pixel(it.top),
                        pixel(it.right),
                        pixel(it.bottom),
                    )
                }
                val previous = platform.windows.SelectObject(
                    dc,
                    when (size) {
                        28 -> welcome
                        23 -> title
                        12 -> caption
                        else -> body
                    },
                )
                platform.windows.SetBkMode(dc, platform.windows.TRANSPARENT)
                platform.windows.SetTextColor(dc, color)
                val rect = alloc<RECT>()
                rect.left = pixel(x)
                rect.right = pixel(x + width)
                rect.top = pixel(y)
                rect.bottom = pixel(y + if (multiline) MULTILINE_HEIGHT else LINE_HEIGHT)
                val wrapping = if (multiline) platform.windows.DT_WORDBREAK else platform.windows.DT_SINGLELINE
                val flags = alignment or wrapping
                platform.windows.DrawTextW(dc, value, -1, rect.ptr, flags.toUInt())
                platform.windows.SelectObject(dc, previous)
                platform.windows.RestoreDC(dc, saved)
            }
        }
    }

    fun path(points: List<LauncherPoint>, color: UInt, stroke: Int = 2) = path(points, color, stroke.toDouble())

    fun path(points: List<LauncherPoint>, color: UInt, stroke: Double) = memScoped {
        if (points.size < 2) return@memScoped
        val padding = stroke / 2 + 1
        cover(
            points.minOf { it.x } - padding,
            points.minOf { it.y } - padding,
            points.maxOf { it.x } + padding,
            points.maxOf { it.y } + padding,
        )
        val dc = checkNotNull(memory)
        val saved = platform.windows.SaveDC(dc)
        platform.windows.SetMapMode(dc, platform.windows.MM_TEXT)
        val pen = platform.windows.CreatePen(platform.windows.PS_SOLID, (stroke * SCALE * density).roundToInt(), color)
        val old = platform.windows.SelectObject(dc, pen)
        val data = allocArray<POINT>(points.size)
        points.forEachIndexed { index, point ->
            data[index].x = (point.x * SCALE * density).roundToInt()
            data[index].y = (point.y * SCALE * density).roundToInt()
        }
        platform.windows.Polyline(dc, data, points.size)
        platform.windows.SelectObject(dc, old)
        platform.windows.DeleteObject(pen)
        platform.windows.RestoreDC(dc, saved)
    }

    fun clipped(left: Int, top: Int, right: Int, bottom: Int, draw: () -> Unit) {
        val dc = checkNotNull(memory)
        val saved = platform.windows.SaveDC(dc)
        platform.windows.IntersectClipRect(dc, left, top, right, bottom)
        textClips += TextClip(left, top, right, bottom)
        try {
            draw()
        } finally {
            textClips.removeAt(textClips.lastIndex)
            platform.windows.RestoreDC(dc, saved)
        }
    }

    /** Original brand silhouette; fill is clipped before the gradient, so the outside remains fully opaque. */
    fun logo(points: List<LauncherPoint>, x: Int, y: Int, size: Int, opacity: Double, background: UInt) = memScoped {
        cover(x.toDouble(), y.toDouble(), (x + size).toDouble(), (y + size).toDouble())
        val dc = checkNotNull(memory)
        val saved = platform.windows.SaveDC(dc)
        platform.windows.SetMapMode(dc, platform.windows.MM_TEXT)
        platform.windows.BeginPath(dc)
        points.forEachIndexed { index, point ->
            if (index == 0) {
                platform.windows.MoveToEx(
                    dc,
                    (point.x * SCALE * density).roundToInt(),
                    (point.y * SCALE * density).roundToInt(),
                    null,
                )
            } else {
                platform.windows.LineTo(
                    dc,
                    (point.x * SCALE * density).roundToInt(),
                    (point.y * SCALE * density).roundToInt(),
                )
            }
        }
        platform.windows.CloseFigure(dc)
        platform.windows.EndPath(dc)
        platform.windows.SelectClipPath(dc, platform.windows.RGN_AND)
        platform.windows.SetMapMode(dc, platform.windows.MM_ANISOTROPIC)
        platform.windows.SetWindowExtEx(dc, width, height, null)
        platform.windows.SetViewportExtEx(dc, pixelWidth * SCALE, pixelHeight * SCALE, null)
        for (row in 0 until size) {
            val color = launcherBlend(LauncherBrand.BLUE, LauncherBrand.VIOLET, 1 - row.toDouble() / size)
            fill(x, y + row, size, 1, launcherBlend(color, background, opacity))
        }
        platform.windows.RestoreDC(dc, saved)
    }

    private fun pixel(value: Int): Int = (value * density).roundToInt()

    private fun cover(left: Double, top: Double, right: Double, bottom: Double) {
        coverage += TextClip(
            floor(left * density).toInt().coerceIn(0, pixelWidth),
            floor(top * density).toInt().coerceIn(0, pixelHeight),
            ceil(right * density).toInt().coerceIn(0, pixelWidth),
            ceil(bottom * density).toInt().coerceIn(0, pixelHeight),
        )
    }

    private fun releaseBuffer() {
        capacityWidth = 0
        capacityHeight = 0
        output?.let { dc ->
            platform.windows.SelectObject(dc, originalOutputBitmap)
            outputBitmap?.let { platform.windows.DeleteObject(it) }
            platform.windows.DeleteDC(dc)
        }
        output = null
        outputBitmap = null
        originalOutputBitmap = null
        outputPixels = null
        memory?.let { dc ->
            platform.windows.SelectObject(dc, originalBitmap)
            bitmap?.let { platform.windows.DeleteObject(it) }
            platform.windows.DeleteDC(dc)
        }
        memory = null
        bitmap = null
        originalBitmap = null
        pixels = null
    }

    override fun close() {
        releaseBuffer()
        listOf(body, caption, title, welcome).forEach { platform.windows.DeleteObject(it) }
    }

    companion object {
        private const val SCALE = 4
        private const val SHIMMER_HALF_WIDTH = 120
        private const val BUFFER_STEP = 128
        private const val MULTILINE_HEIGHT = 90
        private const val LINE_HEIGHT = 32
    }
}

/** COLORREF interpolation also supplies opacity over an already known opaque scene background. */
internal fun launcherBlend(front: UInt, back: UInt, opacity: Double): UInt {
    val alpha = opacity.coerceIn(0.0, 1.0)
    var color = 0u
    for (shift in listOf(0, CHANNEL_BITS, CHANNEL_BITS * 2)) {
        val a = ((front shr shift) and 255u).toInt()
        val b = ((back shr shift) and 255u).toInt()
        color = color or ((b + (a - b) * alpha).roundToInt().toUInt() shl shift)
    }
    return color
}

private const val CHANNEL_BITS = 8

private data class TextClip(val left: Int, val top: Int, val right: Int, val bottom: Int)
