package io.aequicor.heartbeat.feature.computeruse.api

import kotlinx.serialization.Serializable

/** Container format of a stored frame. */
public enum class CaptureFormat {
    /** Lossless; the master frame and high-resolution crops always use it. */
    Png,

    /** Lossy with a quality factor; the smallest option for overview frames. */
    Jpeg,
}

/** Pixel layout produced before encoding. */
public enum class CaptureColorModel {
    /** Full color. */
    Rgb,

    /** One byte per pixel; the cheapest layout for text-heavy user interfaces. */
    Gray8,

    /** Palette of at most 256 grays with error diffusion. */
    Indexed,
}

/**
 * How a frame is reduced and encoded before it leaves the host.
 *
 * All limits are optional and combine: a frame is downscaled to [maxWidthPx]/[maxHeightPx], converted to
 * [colorModel] and then encoded; when the result exceeds [maxBytes], the encoder lowers [quality] first and the
 * size second, and reports [ComputerUseFailure.EncodingTooLarge] when the limit is unreachable.
 *
 * @property format container of the stored frame.
 * @property quality 1..100, meaningful only for [CaptureFormat.Jpeg].
 * @property colorModel pixel layout produced before encoding.
 * @property isDithered error diffusion for [CaptureColorModel.Gray8] and [CaptureColorModel.Indexed].
 * @property maxWidthPx 0 keeps the source width.
 * @property maxHeightPx 0 keeps the source height.
 * @property maxBytes 0 imposes no hard limit.
 * @property isSharpened sharpening after a resize; it does not add information.
 */
@Serializable
public data class CaptureEncoding(
    public val format: CaptureFormat = CaptureFormat.Png,
    public val quality: Int = DEFAULT_QUALITY,
    public val colorModel: CaptureColorModel = CaptureColorModel.Rgb,
    public val isDithered: Boolean = false,
    public val maxWidthPx: Int = 0,
    public val maxHeightPx: Int = 0,
    public val maxBytes: Int = 0,
    public val isSharpened: Boolean = false,
) {
    init {
        require(quality in 1..MAX_QUALITY) { "CaptureEncoding quality must be in 1..$MAX_QUALITY: $quality" }
        require(maxWidthPx >= 0 && maxHeightPx >= 0) { "CaptureEncoding size limits must not be negative" }
        require(maxBytes >= 0) { "CaptureEncoding byte limit must not be negative: $maxBytes" }
    }

    /** The same encoding without size limits; used for master frames. */
    public fun lossless(): CaptureEncoding = copy(
        format = CaptureFormat.Png,
        quality = DEFAULT_QUALITY,
        colorModel = CaptureColorModel.Rgb,
        isDithered = false,
        maxWidthPx = 0,
        maxHeightPx = 0,
        maxBytes = 0,
        isSharpened = false,
    )

    private companion object {
        const val DEFAULT_QUALITY: Int = 75
        const val MAX_QUALITY: Int = 100
    }
}

/** Named encodings so an agent picks one word instead of eight numeric parameters. */
public object CapturePresets {
    /** Overview frame for a vision model: bounded size, lossy, full color. */
    public val AgentOverview: CaptureEncoding = CaptureEncoding(
        format = CaptureFormat.Jpeg,
        quality = OVERVIEW_QUALITY,
        maxWidthPx = OVERVIEW_MAX_WIDTH_PX,
        maxHeightPx = OVERVIEW_MAX_HEIGHT_PX,
        maxBytes = OVERVIEW_MAX_BYTES,
    )

    /** Text-heavy user interface: grayscale with error diffusion, lossless container. */
    public val AgentText: CaptureEncoding = CaptureEncoding(
        format = CaptureFormat.Png,
        colorModel = CaptureColorModel.Gray8,
        isDithered = true,
        maxWidthPx = OVERVIEW_MAX_WIDTH_PX,
        maxHeightPx = OVERVIEW_MAX_HEIGHT_PX,
        maxBytes = TEXT_MAX_BYTES,
    )

    /** A crop or tile in native resolution; upscaling is requested separately. */
    public val AgentDetail: CaptureEncoding = CaptureEncoding(
        format = CaptureFormat.Png,
        maxBytes = DETAIL_MAX_BYTES,
        isSharpened = true,
    )

    /** Larger JPEG preview an agent can request with the `ui` preset. */
    public val UiPreview: CaptureEncoding = CaptureEncoding(
        format = CaptureFormat.Jpeg,
        quality = UI_QUALITY,
        maxWidthPx = UI_MAX_WIDTH_PX,
        maxHeightPx = UI_MAX_HEIGHT_PX,
    )

    /** Stored master frame: lossless, full resolution, never sent to a model as is. */
    public val Master: CaptureEncoding = CaptureEncoding().lossless()

    /** The preset for a preset name coming from a tool argument; `null` when the name is unknown. */
    public fun byName(name: String): CaptureEncoding? = when (name.lowercase()) {
        "overview" -> AgentOverview
        "text" -> AgentText
        "detail" -> AgentDetail
        "ui" -> UiPreview
        "master" -> Master
        else -> null
    }

    private const val OVERVIEW_QUALITY: Int = 60
    private const val UI_QUALITY: Int = 70
    private const val OVERVIEW_MAX_WIDTH_PX: Int = 1568
    private const val OVERVIEW_MAX_HEIGHT_PX: Int = 1568
    private const val UI_MAX_WIDTH_PX: Int = 720
    private const val UI_MAX_HEIGHT_PX: Int = 720
    private const val OVERVIEW_MAX_BYTES: Int = 300 * 1024
    private const val TEXT_MAX_BYTES: Int = 200 * 1024
    private const val DETAIL_MAX_BYTES: Int = 2 * 1024 * 1024
}

/** One frame request. Geometry is interpreted in master-frame pixels. */
public data class CaptureRequest(
    public val region: CaptureRegion? = null,
    public val tile: TileRef? = null,
    public val encoding: CaptureEncoding = CapturePresets.AgentOverview,
    public val isCursorIncluded: Boolean = true,
    public val isFresh: Boolean = true,
) {
    init {
        require(region == null || tile == null) { "A capture request selects either a region or a tile" }
    }
}

/** One tile of the master frame grid. */
public data class TileRef(public val column: Int, public val row: Int) {
    init {
        require(column >= 0 && row >= 0) { "TileRef must not be negative: $column:$row" }
    }

    /** Parses `column:row`; `null` when the text has another shape. */
    public companion object {
        /** Parses the `2:1` form; returns `null` for anything else. */
        public fun parse(value: String): TileRef? {
            val parts = value.split(':')
            if (parts.size != 2) return null
            val column = parts[0].trim().toIntOrNull() ?: return null
            val row = parts[1].trim().toIntOrNull() ?: return null
            return if (column >= 0 && row >= 0) TileRef(column, row) else null
        }
    }
}

/** A crop request against one stored master frame. */
public data class CropRequest(
    public val capture: CaptureId,
    public val region: CaptureRegion? = null,
    public val normalized: NormalizedRegion? = null,
    public val scale: Double = 1.0,
    public val encoding: CaptureEncoding = CapturePresets.AgentDetail,
) {
    init {
        require(region == null || normalized == null) { "A crop selects either master pixels or fractions" }
        require(scale in MIN_SCALE..MAX_SCALE) { "Crop scale must be in $MIN_SCALE..$MAX_SCALE: $scale" }
    }

    private companion object {
        const val MIN_SCALE: Double = 1.0
        const val MAX_SCALE: Double = 4.0
    }
}

/** A rectangle in fractions `0.0..1.0` of a master frame; independent of the preview scale an agent saw. */
public data class NormalizedRegion(
    public val x: Double,
    public val y: Double,
    public val width: Double,
    public val height: Double,
) {
    init {
        require(width > 0.0 && height > 0.0) { "NormalizedRegion size must be positive: $width x $height" }
        require(x >= 0.0 && y >= 0.0) { "NormalizedRegion origin must not be negative: $x,$y" }
        require(x + width <= 1.0 && y + height <= 1.0) { "NormalizedRegion must stay inside the frame" }
    }

    /** The region in pixels of a `widthPx x heightPx` frame; always at least one pixel wide and high. */
    public fun toRegion(widthPx: Int, heightPx: Int): CaptureRegion {
        val left = (x * widthPx).toInt().coerceIn(0, (widthPx - 1).coerceAtLeast(0))
        val top = (y * heightPx).toInt().coerceIn(0, (heightPx - 1).coerceAtLeast(0))
        val right = ((x + width) * widthPx).toInt().coerceIn(left + 1, widthPx)
        val bottom = ((y + height) * heightPx).toInt().coerceIn(top + 1, heightPx)
        return CaptureRegion(left, top, right - left, bottom - top)
    }
}

/** Pixel size of one frame. */
public data class Dimensions(public val widthPx: Int, public val heightPx: Int) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Dimensions must be positive: ${widthPx}x$heightPx" }
    }

    /** Width divided by height. */
    public val aspectRatio: Double get() = widthPx.toDouble() / heightPx.toDouble()
}

/** How one provider prices an image; both strategies are pure arithmetic. */
public sealed interface VisionCost {
    /** Estimated tokens of a `widthPx x heightPx` image. */
    public fun tokens(widthPx: Int, heightPx: Int): Int

    /** One token per [pixelsPerToken] pixels, the pricing of Anthropic-class models. */
    public data class PixelsPerToken(public val pixelsPerToken: Int = DEFAULT_PIXELS_PER_TOKEN) : VisionCost {
        init {
            require(pixelsPerToken > 0) { "pixelsPerToken must be positive: $pixelsPerToken" }
        }

        override fun tokens(widthPx: Int, heightPx: Int): Int {
            val pixels = widthPx.toLong() * heightPx.toLong()
            return ceilDiv(pixels, pixelsPerToken.toLong()).toInt()
        }
    }

    /** A base cost plus one cost per fixed square tile, the pricing of OpenAI-class models. */
    public data class TiledSquare(
        public val tilePx: Int = DEFAULT_TILE_PX,
        public val baseTokens: Int = DEFAULT_BASE_TOKENS,
        public val perTileTokens: Int = DEFAULT_PER_TILE_TOKENS,
    ) : VisionCost {
        init {
            require(tilePx > 0 && baseTokens >= 0 && perTileTokens > 0) { "TiledSquare parameters must be positive" }
        }

        override fun tokens(widthPx: Int, heightPx: Int): Int {
            val columns = ceilDiv(widthPx.toLong(), tilePx.toLong()).toInt()
            val rows = ceilDiv(heightPx.toLong(), tilePx.toLong()).toInt()
            return baseTokens + columns * rows * perTileTokens
        }
    }
}

/**
 * Token budget of one frame sent to a model.
 *
 * [dimensionsFor] returns the largest even-sized reduction of the native frame that fits [maxTokens], so the
 * host can promise a cost before encoding instead of retrying after the provider rejected an image.
 */
public data class VisionBudget(public val maxTokens: Int, public val cost: VisionCost = VisionCost.PixelsPerToken()) {
    init {
        require(maxTokens > 0) { "VisionBudget must be positive: $maxTokens" }
    }

    /** `true` when a frame of this size fits the budget. */
    public fun isAffordable(widthPx: Int, heightPx: Int): Boolean = cost.tokens(widthPx, heightPx) <= maxTokens

    /** The largest affordable reduction of `nativeWidthPx x nativeHeightPx`, with even sides. */
    public fun dimensionsFor(nativeWidthPx: Int, nativeHeightPx: Int): Dimensions {
        require(nativeWidthPx > 0 && nativeHeightPx > 0) { "Native frame must have a positive size" }
        if (isAffordable(nativeWidthPx, nativeHeightPx)) return evenSides(nativeWidthPx, nativeHeightPx)
        var low = 0.0
        var high = 1.0
        repeat(SEARCH_STEPS) {
            val middle = (low + high) / 2.0
            val candidate = scaled(nativeWidthPx, nativeHeightPx, middle)
            if (isAffordable(candidate.widthPx, candidate.heightPx)) low = middle else high = middle
        }
        return scaled(nativeWidthPx, nativeHeightPx, low)
    }
}

private const val DEFAULT_PIXELS_PER_TOKEN: Int = 750
private const val DEFAULT_TILE_PX: Int = 512
private const val DEFAULT_BASE_TOKENS: Int = 85
private const val DEFAULT_PER_TILE_TOKENS: Int = 170
private const val SEARCH_STEPS: Int = 24
private const val MINIMUM_SIDE_PX: Int = 2

private fun ceilDiv(value: Long, step: Long): Long = (value + step - 1) / step

private fun scaled(widthPx: Int, heightPx: Int, factor: Double): Dimensions = Dimensions(
    widthPx = evenSide(widthPx * factor),
    heightPx = evenSide(heightPx * factor),
)

private fun evenSides(widthPx: Int, heightPx: Int): Dimensions =
    Dimensions(evenSide(widthPx.toDouble()), evenSide(heightPx.toDouble()))

private fun evenSide(value: Double): Int {
    val rounded = kotlin.math.round(value).toInt()
    return if (rounded < MINIMUM_SIDE_PX) MINIMUM_SIDE_PX else rounded - rounded % MINIMUM_SIDE_PX
}
