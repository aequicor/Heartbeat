package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserTab
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The icon found for [pageUrl]; it stays with the tab while the tab shows a page of the same origin. */
internal data class DesktopBrowserFavicon(val pageUrl: String, val url: String)

/** Retained native tab state shared by the session coordinator and its load observers. */
internal class DesktopBrowserEntry(
    var tab: BrowserTab,
    val surface: BrowserSurfaceAdapter,
    val guard: DesktopBrowserAgentGuard,
    panel: DesktopBrowserPanel,
) {
    val driver = DesktopBrowserAutomation(panel::devTools, panel::captureResource)
    val favicons = DesktopBrowserFaviconProbe(panel::devTools)
    val loadIcon: suspend (String) -> ByteArray = panel::loadIcon
    val interstitial = DesktopBrowserInterstitial(panel::devTools)
    val lock = Mutex()
    var observer: Job? = null
    var faviconLookup: Job? = null
    var favicon: DesktopBrowserFavicon? = null

    /**
     * Documents of an agent tab are limited to the public web and the origin of [approved]; the
     * user's own tab, or one the user currently controls, is unrestricted.
     */
    fun syncGuard(approved: String?) {
        if (tab.owner == null || tab.isHumanControlled) guard.unrestrict() else guard.restrict(approved)
    }

    @HighFrequency
    fun update(page: BrowserPage) {
        if (page.url != tab.page.url || page.isLoading) driver.invalidate()
        val isPendingTitle = page.isLoading && page.url == tab.page.url
        val title = if (isPendingTitle) page.title.ifBlank { tab.page.title } else page.title
        val icon = favicon?.takeIf { browserOrigin(it.pageUrl) == browserOrigin(page.url) }?.url.orEmpty()
        tab = tab.copy(page = page.copy(title = title, faviconUrl = icon))
    }

    fun result(request: BrowserRequest) = BrowserResult(
        request.id,
        buildJsonObject {
            put("tabId", tab.id)
            put("url", surface.pages.value.url)
            put("title", surface.pages.value.title)
        },
    )
}
