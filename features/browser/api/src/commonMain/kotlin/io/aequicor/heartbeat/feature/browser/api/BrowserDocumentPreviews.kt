package io.aequicor.heartbeat.feature.browser.api

import kotlinx.serialization.json.JsonElement

/**
 * One-shot visual checks of supplied HTML in the isolated document renderer. A preview has no owner bridge,
 * direct network connections, saved browser context or library registration. Its trusted
 * [BrowserDocumentPreviewRequest] may permit bounded external assets, using the same resource policy as visible
 * documents. It never captures a desktop window or another page.
 * Desktop only; unsupported platforms return [BrowserDocumentPreviewResult.Unavailable].
 */
public interface BrowserDocumentPreviews {
    /** Renders a bounded viewport and diagnostics, disposing its document on success, failure or cancellation. */
    public suspend fun preview(request: BrowserDocumentPreviewRequest): BrowserDocumentPreviewResult
}

/**
 * An independent page with sample [data], measured in CSS pixels at device scale one. [isDark] and [fontScale]
 * select reproducible host design-system tokens. HTML and data use [BrowserDocumentLimits]; viewport and output
 * use [BrowserDocumentPreviewLimits]. [resourcePolicy] is chosen by the trusted opener, not by page source.
 * There is deliberately no owner, callback, URL or script-evaluation option.
 */
public data class BrowserDocumentPreviewRequest(
    val html: String,
    val data: JsonElement? = null,
    val width: Int = 900,
    val height: Int = 640,
    val isDark: Boolean = false,
    val fontScale: Float = 1f,
    val resourcePolicy: BrowserDocumentResourcePolicy = BrowserDocumentResourcePolicy.Isolated,
) {
    override fun toString(): String = "BrowserDocumentPreviewRequest(${width}x$height,dark=$isDark,scale=$fontScale)"
}

/** An isolated render's image and bounded diagnostics, or a reason it could not be checked. */
public sealed interface BrowserDocumentPreviewResult {
    /** PNG of the requested viewport; issues are observations to inspect, not proof of a fully correct app. */
    public data class Rendered(
        val imageBase64: String,
        val metrics: BrowserDocumentPreviewMetrics,
        val issues: List<BrowserDocumentPreviewIssue>,
        val issuesTruncated: Boolean = false,
        val samples: List<BrowserDocumentPreviewElement> = emptyList(),
    ) : BrowserDocumentPreviewResult {
        override fun toString(): String = "Rendered(metrics=$metrics,issues=${issues.size},truncated=$issuesTruncated)"
    }

    /** This platform has no isolated visual renderer. */
    public data object Unavailable : BrowserDocumentPreviewResult

    /** A bounded, redacted reason: page text and native/JavaScript exception messages are never exposed here. */
    public data class Failure(val reason: BrowserDocumentPreviewFailure) : BrowserDocumentPreviewResult
}

/** Stable failures let callers explain recovery without leaking page data or native exception messages. */
public enum class BrowserDocumentPreviewFailure {
    /** HTML/data, viewport or text scale lies outside its limits. */
    InvalidRequest,

    /** Another preview is rendering or the profile's documents are at their limit; callers can retry later. */
    Busy,

    /**
     * The native runtime could not be prepared or is not installed yet: a preview never starts the first download,
     * which a visible web document performs with progress and retry.
     */
    RuntimeUnavailable,

    /** The page did not produce a bounded result before the deadline. */
    TimedOut,

    /** Native creation, layout inspection or image capture failed. */
    RenderFailed,

    /** The captured PNG exceeds the output byte limit. */
    ImageTooLarge,
}

/** Actual viewport and document extent in CSS pixels; a tall page is allowed to scroll vertically. */
public data class BrowserDocumentPreviewMetrics(
    val viewportWidth: Int,
    val viewportHeight: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val horizontalOverflow: Boolean,
)

/** One visible element's diagnostic. [element] is a bounded DOM identifier, not its private text or value. */
public data class BrowserDocumentPreviewIssue(val kind: BrowserDocumentPreviewIssueKind, val element: String)

/**
 * Computed geometry and styles of body or a visible heading, control or structural landmark. No text content,
 * input value or owner data is included. Coordinates are CSS pixels, saturated at
 * [BrowserDocumentPreviewLimits.METRIC];
 * strings and the sample count are bounded. These measurements support inspection, not an aesthetic verdict.
 */
public data class BrowserDocumentPreviewElement(
    val element: String,
    val tag: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val fontFamily: String,
    val fontSize: Double,
    val lineHeight: String,
    val color: String,
    val backgroundColor: String,
)

/** Layout and semantics observations available without clicking controls or calling an owner method. */
public enum class BrowserDocumentPreviewIssueKind {
    /** The document or a visible element extends beyond the horizontal viewport. */
    HorizontalOverflow,

    /** Visible text is smaller than the host's scaled caption size. */
    SmallText,

    /** A visible form field has no accessible label. */
    UnlabelledInput,

    /** A visible button has no accessible name. */
    UnnamedButton,

    /** The page references an external resource blocked by document isolation. */
    ExternalResource,

    /** Chromium reported an error (including script, console or policy errors); its private text is omitted. */
    ScriptError,
}

/** Input and output bounds of temporary previews, independent of persistent document registrations. */
public object BrowserDocumentPreviewLimits {
    /** Smallest preview width in CSS pixels. */
    public const val MIN_WIDTH: Int = 320

    /** Largest preview width in CSS pixels. */
    public const val MAX_WIDTH: Int = 1600

    /** Smallest preview height in CSS pixels. */
    public const val MIN_HEIGHT: Int = 240

    /** Largest preview height in CSS pixels. */
    public const val MAX_HEIGHT: Int = 1200

    /** Smallest host text scale. */
    public const val MIN_FONT_SCALE: Float = 0.8f

    /** Largest host text scale. */
    public const val MAX_FONT_SCALE: Float = 2f

    /** Maximum decoded PNG bytes returned by a preview. */
    public const val IMAGE_BYTES: Int = 4 * 1024 * 1024

    /** Maximum reported issues; the result explicitly records truncation. */
    public const val ISSUES: Int = 40

    /** Maximum sampled visible elements, including the document body. */
    public const val SAMPLES: Int = 20

    /** Maximum characters of an element identifier or a computed style value. */
    public const val TEXT_CHARS: Int = 160

    /** Geometry beyond this many CSS pixels is saturated in the diagnostic output. */
    public const val METRIC: Int = 1_000_000
}
