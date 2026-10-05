package io.aequicor.heartbeat.feature.plantumlsupport.api

import kotlinx.coroutines.flow.Flow

/**
 * Draws PlantUML diagrams of Markdown fences locally, without network access. Only a host that renders
 * locally binds it (desktop); the feature toggle switches it on. Untrusted sources are expected: the engine reads
 * no files, URLs or environment variables, runs apart from the app with bounded memory and time, and only draws
 * diagram types that need no external tools.
 */
public interface PlantUmlRenderer {
    /** Emits whether [render] draws diagrams now; follows the feature toggle. */
    public val availability: Flow<Boolean>

    /**
     * Draws [request]. Main-safe; never throws except for cancellation — failures are [PlantUmlResult]s.
     * Identical requests share one drawing and recent results are cached.
     */
    public suspend fun render(request: PlantUmlRequest): PlantUmlResult
}

/**
 * Theme of a drawn diagram: ARGB colors (`0xAARRGGBB`) and font size in logical units. The background is always
 * transparent; the host's panel supplies it.
 */
public data class PlantUmlStyle(
    val text: Int,
    val secondaryText: Int,
    val line: Int,
    val fill: Int,
    val border: Int,
    val accentFill: Int,
    val fontSize: Float,
    val isDark: Boolean,
)

/** A fence [source] drawn with [style] at [scale] pixels per logical unit (display density). */
public data class PlantUmlRequest(val source: String, val style: PlantUmlStyle, val scale: Float)

/** Why a diagram was not drawn. [Timeout] and [Busy] are transient. */
public enum class PlantUmlFailure {
    /** The source or the drawn image exceeds the limits. */
    TooLarge,

    /** Drawing took longer than allowed. */
    Timeout,

    /** An earlier drawing that timed out still occupies the engine. */
    Busy,

    /** The engine failed for another reason; details are in the log. */
    Internal,
}

/** Outcome of [PlantUmlRenderer.render]. */
public sealed interface PlantUmlResult {
    /**
     * A PNG [png] of the first page, drawn at [scale] pixels per logical unit; [width] × [height] is the logical size.
     * Equality is by identity.
     */
    public class Image(
        public val png: ByteArray,
        public val width: Float,
        public val height: Float,
        public val scale: Float,
    ) : PlantUmlResult

    /** The source is invalid; [line] is 1-based in the fence source, null when unknown. */
    public data class SyntaxError(val line: Int?, val message: String) : PlantUmlResult

    /** Drawing failed for a [reason] other than the source itself. */
    public data class Failed(val reason: PlantUmlFailure) : PlantUmlResult

    /** Not drawn here: the renderer is off, or the diagram type needs external tools. Show the source instead. */
    public data object Unsupported : PlantUmlResult
}
