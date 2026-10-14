package io.aequicor.heartbeat.feature.chromiumbridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `diagram.render` parameters. The app draws the untrusted [source] locally, using its sandboxed renderer. */
@Serializable
public data class DiagramRenderRequest(val source: String, val style: DiagramStyle, val scale: Float) {
    override fun toString(): String = "DiagramRenderRequest(sourceCharacters=${source.length}, scale=$scale)"
}

/** ARGB colors and logical font size of the panel's current theme; diagram backgrounds stay transparent. */
@Serializable
public data class DiagramStyle(
    val text: Int,
    val secondaryText: Int,
    val line: Int,
    val fill: Int,
    val border: Int,
    val accentFill: Int,
    val fontSize: Float,
    val isDark: Boolean,
)

/** `diagram.render` result, serialized with the bridge's `kind` discriminator. */
@Serializable
public sealed interface DiagramRenderResult {
    /** First diagram page as base64 PNG; [width] and [height] and all [links] use logical image units. */
    @Serializable
    @SerialName("image")
    public data class Image(
        val png: String,
        val width: Float,
        val height: Float,
        val scale: Float,
        val links: List<DiagramLink> = emptyList(),
    ) : DiagramRenderResult {
        override fun toString(): String = "Image(width=$width, height=$height, scale=$scale)"
    }

    /** Invalid source; [line] is 1-based in the original fence, when the engine identifies it. */
    @Serializable
    @SerialName("syntaxError")
    public data class SyntaxError(val line: Int?, val message: String) : DiagramRenderResult {
        override fun toString(): String = "SyntaxError(line=${line ?: "unknown"})"
    }

    /** Drawing failed; timeouts and busy results can be retried by the user. */
    @Serializable
    @SerialName("failed")
    public data class Failed(val reason: DiagramFailure) : DiagramRenderResult

    /** Drawing is disabled or the local renderer does not support this diagram; display the source. */
    @Serializable
    @SerialName("unsupported")
    public data object Unsupported : DiagramRenderResult
}

/** A rectangle emitted by the renderer; [destination] is inert data for the panel's link policy. */
@Serializable
public data class DiagramLink(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val destination: String,
    val tooltip: String,
) {
    override fun toString(): String = "DiagramLink(left=$left, top=$top, right=$right, bottom=$bottom)"
}

/** Stable reasons for an unsuccessful local drawing. */
@Serializable
public enum class DiagramFailure { TooLarge, Timeout, Busy, Internal }

/** Bounds agreed by the side panel and the bridge, matching the local renderer's image limits. */
public object DiagramRenderLimits {
    public const val MAX_SOURCE_CHARACTERS: Int = 32 * 1024
    public const val MAX_PNG_BYTES: Int = 10 * 1024 * 1024
    public const val MAX_SCALE: Float = 3f
    public const val MAX_FONT_SIZE: Float = 256f
}
