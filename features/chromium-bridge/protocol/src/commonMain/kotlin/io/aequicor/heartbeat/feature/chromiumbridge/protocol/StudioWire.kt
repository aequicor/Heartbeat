package io.aequicor.heartbeat.feature.chromiumbridge.protocol

import kotlinx.serialization.Serializable

/** `watch.cancel` parameters and the result of every watch. */
@Serializable
public data class WatchRef(public val watch: String)

/** A page the extension captured: its address, title, the user's selection and its readable text. */
@Serializable
public data class WirePage(
    public val kind: String = "page",
    public val url: String,
    public val title: String = "",
    public val text: String = "",
    public val selection: String? = null,
) {
    /** Bounds the extension promised to respect. */
    public fun isValid(): Boolean = kind == "page" && url.length <= PageCaptureLimits.MAX_URL_CHARS &&
        (url.startsWith("https://") || url.startsWith("http://")) &&
        title.length <= PageCaptureLimits.MAX_TITLE_CHARS && text.length <= PageCaptureLimits.MAX_PAGE_CHARS &&
        (selection?.length ?: 0) <= PageCaptureLimits.MAX_SELECTION_CHARS
}

/** Bounds shared with the browser page capture implementation. */
public object PageCaptureLimits {
    public const val MAX_TITLE_CHARS: Int = 512
    public const val MAX_URL_CHARS: Int = 4096
    public const val MAX_PAGE_CHARS: Int = 200_000
    public const val MAX_SELECTION_CHARS: Int = 20_000
}
