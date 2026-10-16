package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed

/** A `<link>` of the loaded document that declares a site icon; values come from the page and are untrusted. */
internal data class BrowserFaviconCandidate(
    val url: String,
    val rel: String,
    val sizes: String = "",
    val type: String = "",
)

/** Whether site icons are looked up for loaded pages. */
internal interface BrowserFaviconPolicy {
    suspend fun isEnabled(): Boolean
}

/**
 * Picks the icon a tab shows for [pageUrl], as Chromium does for its tab strip: a declared `icon` before an Apple touch
 * icon, a scalable or exactly sized icon closest to [BROWSER_FAVICON_PIXELS], the first one among equals, and the
 * origin's `/favicon.ico` when the page declares none. Only HTTP(S) icons of an HTTP(S) page are chosen; inline
 * `data:` icons are ignored.
 */
internal fun chooseBrowserFavicon(pageUrl: String, candidates: List<BrowserFaviconCandidate>): String? {
    if (!isBrowserUrlAllowed(pageUrl)) return null
    val declared = candidates.asSequence()
        .filter { it.url.length <= MAX_FAVICON_URL_LENGTH && isBrowserUrlAllowed(it.url) }
        .mapNotNull { candidate -> candidate.rank()?.let { candidate to it } }
        .minByOrNull { it.second }
    return declared?.first?.url ?: defaultFavicon(pageUrl)
}

/** Null for a link that declares no site icon; otherwise lower is better: the kind first, then the size penalty. */
private fun BrowserFaviconCandidate.rank(): Int? {
    val rels = rel.lowercase().split(' ', '\t', '\n').filter { it.isNotEmpty() }
    val kind = when {
        "icon" in rels -> 0
        rels.any { it in TOUCH_ICON_RELS } -> 1
        else -> return null
    }
    val declaredSizes = sizes.lowercase().split(' ').filter { it.isNotEmpty() }
    val penalty = when {
        type.equals(SVG_TYPE, ignoreCase = true) || "any" in declaredSizes -> 0
        declaredSizes.isEmpty() -> UNDECLARED_SIZE_PENALTY
        else -> declaredSizes.mapNotNull(::sizePenalty).minOrNull() ?: UNDECLARED_SIZE_PENALTY
    }
    return kind * KIND_WEIGHT + penalty
}

private fun sizePenalty(size: String): Int? {
    val width = size.substringBefore('x').toIntOrNull() ?: return null
    val height = size.substringAfter('x', missingDelimiterValue = "").toIntOrNull() ?: return null
    val pixels = maxOf(width, height)
    return when {
        pixels !in 1..MAX_DECLARED_PIXELS -> null

        pixels >= BROWSER_FAVICON_PIXELS -> pixels - BROWSER_FAVICON_PIXELS

        // Upscaling blurs more than downscaling, so a smaller icon loses to a larger one at the same distance.
        else -> (BROWSER_FAVICON_PIXELS - pixels) * 2
    }
}

private fun defaultFavicon(pageUrl: String): String {
    val authorityStart = pageUrl.indexOf("://") + SCHEME_SEPARATOR_LENGTH
    val authorityEnd = pageUrl.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 }
        ?: pageUrl.length
    return pageUrl.substring(0, authorityEnd) + "/favicon.ico"
}

/** Pixels of the bitmap a tab icon is drawn from: the icon's size on a high-density display. */
internal const val BROWSER_FAVICON_PIXELS = 32

/** Longer icon addresses are ignored rather than stored with the tab. */
internal const val MAX_FAVICON_URL_LENGTH = 2_048

private const val SCHEME_SEPARATOR_LENGTH = 3
private const val MAX_DECLARED_PIXELS = 4_096
private const val KIND_WEIGHT = 10_000
private const val UNDECLARED_SIZE_PENALTY = 8
private const val SVG_TYPE = "image/svg+xml"
private val TOUCH_ICON_RELS = setOf("apple-touch-icon", "apple-touch-icon-precomposed")
