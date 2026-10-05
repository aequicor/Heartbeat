package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize

/**
 * Colors and text size a diagram is drawn with, taken from the current theme so a diagram reads as part of the
 * conversation. The background is always transparent: the diagram panel supplies it. [fontSize] is in dp.
 */
@Immutable
public data class HbDiagramStyle(
    val text: Color,
    val secondaryText: Color,
    val line: Color,
    val fill: Color,
    val border: Color,
    val accentFill: Color,
    val fontSize: Float,
    val isDark: Boolean,
)

/** One diagram to draw; [density] is physical pixels per dp of the display, so the result can be drawn sharp. */
@Immutable
public data class HbDiagramRequest(
    val language: HbDiagramLanguage,
    val source: String,
    val style: HbDiagramStyle,
    val density: Float,
)

/** Why a diagram could not be drawn. [Timeout] and [Busy] are transient and offer a retry. */
public enum class HbDiagramFailure {
    TooLarge,
    Timeout,
    Busy,
    Internal,
}

/** Outcome of [HbDiagramRenderer.render]. */
@Immutable
public sealed interface HbDiagramResult {
    /** A drawn diagram: [bitmap] in physical pixels, shown at its logical [size]. Equal only for the same bitmap. */
    @Immutable
    public data class Image(val bitmap: ImageBitmap, val size: DpSize) : HbDiagramResult

    /** The source is not valid; [line] is 1-based in the fence source, null when the renderer cannot tell. */
    @Immutable
    public data class SyntaxError(val line: Int?, val message: String) : HbDiagramResult

    /** Drawing failed for a [reason] other than the source itself. */
    @Immutable
    public data class Failed(val reason: HbDiagramFailure) : HbDiagramResult

    /** This renderer does not draw the source here; the row shows it as code. */
    public data object Unsupported : HbDiagramResult
}

/**
 * Draws diagram fences of Markdown. The design system has no dispatchers: implementations move blocking work
 * (layout, encoding, decoding — see [decodeHbImageBitmap]) off the main thread themselves, return
 * [HbDiagramResult.Unsupported] for sources they do not draw and report failures as results instead of throwing.
 */
@Stable
public fun interface HbDiagramRenderer {
    /** Draws [request]; cancelled when its row leaves composition or the request changes. */
    public suspend fun render(request: HbDiagramRequest): HbDiagramResult
}

/**
 * Localized text of diagram rows and the full-size viewer; English defaults keep previews readable. The syntax error
 * templates place the renderer's message at `{message}` and the 1-based line at `{line}`, in the language's word order.
 */
@Immutable
public data class HbDiagramLabels(
    val rendering: String = "Rendering diagram…",
    val diagram: String = "Diagram",
    val openFullSize: String = "Open full size",
    val close: String = "Close",
    val syntaxErrorAtLine: String = "Diagram error · line {line}: {message}",
    val syntaxError: String = "Diagram error: {message}",
    val tooLarge: String = "The diagram is too large to draw.",
    val timeout: String = "The diagram took too long to draw.",
    val busy: String = "Another diagram is still drawing.",
    val failed: String = "The diagram could not be drawn.",
    val retry: String = "Retry",
    val showSource: String = "Show source",
    val showDiagram: String = "Show diagram",
    val copySource: String = "Copy source",
    val sourceCopied: String = "Source copied",
    val copyFailed: String = "Could not copy",
)

/**
 * Connects a diagram [renderer] to all Markdown below it: diagram fences render as images, errors show the source.
 * Without a renderer (null) diagram fences remain code. Drawn images are kept in a small memory-bounded cache so a
 * row scrolled back into view shows its image immediately.
 */
@Composable
public fun HbDiagramsProvider(
    renderer: HbDiagramRenderer?,
    labels: HbDiagramLabels = HbDiagramLabels(),
    content: @Composable () -> Unit,
) {
    // Drawn images do not depend on the labels: a new locale keeps them.
    val images = remember(renderer) { HbDiagramImageCache() }
    val diagrams = remember(renderer, labels, images) { renderer?.let { HbDiagrams(it, labels, images) } }
    CompositionLocalProvider(LocalHbDiagrams provides diagrams, content = content)
}

/**
 * Decodes an encoded raster image (PNG, JPEG, WebP) within the preview limits of [HbImage]. Blocking: call it off
 * the main thread, for example before returning [HbDiagramResult.Image]. Throws for unsupported or oversized bytes.
 */
public fun decodeHbImageBitmap(bytes: ByteArray): ImageBitmap = decodeAttachmentImage(bytes)

@Stable
internal data class HbDiagrams(
    val renderer: HbDiagramRenderer,
    val labels: HbDiagramLabels,
    val images: HbDiagramImageCache,
)

private val LocalHbDiagrams = staticCompositionLocalOf<HbDiagrams?> { null }

/** The diagram renderer of the enclosing [HbDiagramsProvider], or null when diagrams stay code. */
internal val currentHbDiagrams: HbDiagrams?
    @Composable @ReadOnlyComposable
    get() = LocalHbDiagrams.current

/** Least recently used drawn images, bounded by entry count and decoded pixel bytes. Main thread only. */
internal class HbDiagramImageCache(
    private val maxEntries: Int = MAX_CACHED_DIAGRAMS,
    private val maxBytes: Long = MAX_CACHED_DIAGRAM_BYTES,
) {
    private val entries = LinkedHashMap<HbDiagramRequest, HbDiagramResult.Image>()
    private var bytes = 0L

    /** Reads without changing recency, safe during composition. */
    fun peek(request: HbDiagramRequest): HbDiagramResult.Image? = entries[request]

    fun put(request: HbDiagramRequest, image: HbDiagramResult.Image) {
        entries.remove(request)?.let { bytes -= it.byteSize() }
        entries[request] = image
        bytes += image.byteSize()
        val iterator = entries.entries.iterator()
        // The newest entry always stays, even when it alone exceeds the byte budget.
        while (isOverBudget() && entries.size > 1) {
            bytes -= iterator.next().value.byteSize()
            iterator.remove()
        }
    }

    private fun isOverBudget(): Boolean = entries.size > maxEntries || bytes > maxBytes

    private fun HbDiagramResult.Image.byteSize(): Long = bitmap.width.toLong() * bitmap.height * BYTES_PER_PIXEL
}

private const val MAX_CACHED_DIAGRAMS = 24
private const val MAX_CACHED_DIAGRAM_BYTES = 64L * 1024 * 1024
private const val BYTES_PER_PIXEL = 4
