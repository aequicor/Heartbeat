package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.impl.domain.chooseBrowserFavicon
import io.aequicor.heartbeat.feature.searchengine.api.isPublicWebUrl
import kotlinx.coroutines.Job

/**
 * The site icon of one desktop tab, looked up when its page has loaded and kept while the tab stays on that origin.
 * A failed read of the page still tries the origin's `/favicon.ico`. An agent's restricted tab ([isRestricted]) takes
 * only a public icon or one from the page's own origin, as it could load nothing else, and holds every redirect of the
 * icon to the same rule. [resolved] runs after each lookup; [launch] starts lookups on main. Main-confined.
 */
internal class DesktopBrowserTabFavicon(
    private val launch: (suspend () -> Unit) -> Job,
    private val icons: DesktopBrowserFavicons,
    private val probe: DesktopBrowserFaviconProbe,
    private val loadIcon: suspend (String, (String) -> Boolean) -> ByteArray,
    private val isRestricted: () -> Boolean,
    private val resolved: suspend () -> Unit,
) {
    private val log = Log.tag("BrowserFavicons")
    private var found: Found? = null
    private var lookup: Job? = null

    val isKnown: Boolean
        get() = found != null

    /** An icon saved with the restored tab for [pageUrl]. */
    fun restore(pageUrl: String, url: String) {
        found = Found(pageUrl, url)
    }

    /** The icon of [pageUrl]'s origin, or empty while it is unknown. */
    fun urlFor(pageUrl: String): String =
        found?.takeIf { browserOrigin(it.pageUrl) == browserOrigin(pageUrl) }?.url.orEmpty()

    /**
     * A new load abandons the previous page's lookup; a finished one looks its icon up. The page flow is conflated, so
     * a fast load may never be seen loading: a new address that is already loaded counts as finished too, unless it is
     * a same-document change (history API, fragment) of an origin whose icon is known.
     */
    @HighFrequency
    fun follow(previous: BrowserPage, page: BrowserPage) {
        val isNewDocument = previous.url != page.url && urlFor(page.url).isEmpty()
        if (page.isLoading) {
            lookup?.cancel()
        } else if (page.error == null && (previous.isLoading || isNewDocument)) {
            lookUp(page.url)
        }
    }

    /** Drops the icon and stops a running lookup. */
    fun forget() {
        lookup?.cancel()
        found = null
    }

    fun cancel() {
        lookup?.cancel()
    }

    private fun lookUp(pageUrl: String) {
        lookup?.cancel()
        lookup = launch {
            if (!icons.isEnabled()) return@launch
            val candidates = probe.candidatesOrNone()
            val allows = faviconDestinations(isRestricted(), pageUrl)
            val icon = chooseBrowserFavicon(pageUrl, candidates)?.takeIf(allows)
            log.v { "Site icon resolved: found=${icon != null}" }
            found = icon?.let { Found(pageUrl, it) }
            resolved()
            if (icon != null) icons.load(icon) { loadIcon(it, allows) }
        }
    }

    private data class Found(val pageUrl: String, val url: String)
}

/**
 * The addresses a tab's icon and its redirects may come from: anything for the user's own tab; for an agent's
 * restricted tab, the public web or the page's own origin.
 */
private fun faviconDestinations(isRestricted: Boolean, pageUrl: String): (String) -> Boolean {
    val pageOrigin = browserOrigin(pageUrl)
    return { url -> !isRestricted || isPublicWebUrl(url) || browserOrigin(url) == pageOrigin }
}
