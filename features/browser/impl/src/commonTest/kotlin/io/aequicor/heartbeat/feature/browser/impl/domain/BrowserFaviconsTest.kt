package io.aequicor.heartbeat.feature.browser.impl.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserFaviconsTest {
    @Test
    fun `a declared icon closest to the tab size wins over touch icons and other sizes`() {
        val candidates = listOf(
            BrowserFaviconCandidate("https://example.com/touch.png", "apple-touch-icon", "32x32"),
            BrowserFaviconCandidate("https://example.com/16.png", "icon", "16x16"),
            BrowserFaviconCandidate("https://example.com/192.png", "icon", "192x192"),
            BrowserFaviconCandidate("https://example.com/32.png", "shortcut icon", "48x48 32x32"),
        )
        assertEquals("https://example.com/32.png", chooseBrowserFavicon("https://example.com/", candidates))
    }

    @Test
    fun `icons of any size rank first and equal icons keep document order`() {
        val candidates = listOf(
            BrowserFaviconCandidate("https://example.com/a.ico", "icon"),
            BrowserFaviconCandidate("https://example.com/b.ico", "ICON"),
            BrowserFaviconCandidate("https://example.com/c.png", "icon", "any"),
        )
        assertEquals("https://example.com/c.png", chooseBrowserFavicon("https://example.com/", candidates))
        assertEquals("https://example.com/a.ico", chooseBrowserFavicon("https://example.com/", candidates.take(2)))
    }

    @Test
    fun `svg icons are skipped since they are not decoded`() {
        val candidates = listOf(
            BrowserFaviconCandidate("https://example.com/a.svg", "icon", "any", "image/svg+xml"),
            BrowserFaviconCandidate("https://example.com/b.SVG?v=2", "icon", "32x32"),
            BrowserFaviconCandidate("https://example.com/c.png", "icon", "16x16"),
        )
        assertEquals("https://example.com/c.png", chooseBrowserFavicon("https://example.com/", candidates))
        assertEquals(
            "https://example.com/favicon.ico",
            chooseBrowserFavicon("https://example.com/", candidates.take(2)),
        )
    }

    @Test
    fun `unsafe or foreign links fall back to the origin icon`() {
        val candidates = listOf(
            BrowserFaviconCandidate("data:image/png;base64,AAAA", "icon"),
            BrowserFaviconCandidate("javascript:alert(1)", "icon"),
            BrowserFaviconCandidate("https://example.com/style.css", "stylesheet"),
            BrowserFaviconCandidate("https://example.com/" + "a".repeat(MAX_FAVICON_URL_LENGTH), "icon"),
        )
        assertEquals(
            "https://example.com:8443/favicon.ico",
            chooseBrowserFavicon("https://example.com:8443/docs/page?q=1#top", candidates),
        )
        assertEquals("http://example.com/favicon.ico", chooseBrowserFavicon("http://example.com", emptyList()))
    }

    @Test
    fun `pages outside the web have no icon`() {
        assertNull(chooseBrowserFavicon("about:blank", emptyList()))
        assertNull(chooseBrowserFavicon("", listOf(BrowserFaviconCandidate("https://example.com/i.png", "icon"))))
    }
}
