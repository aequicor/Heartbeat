package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy
import org.cef.network.CefRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDocumentAssetPolicyTest {
    @Test
    fun `external policy admits only main frame GET asset requests`() {
        for (type in CefRequest.ResourceType.entries) {
            val expected = type in setOf(
                CefRequest.ResourceType.RT_STYLESHEET,
                CefRequest.ResourceType.RT_SCRIPT,
                CefRequest.ResourceType.RT_IMAGE,
                CefRequest.ResourceType.RT_FONT_RESOURCE,
                CefRequest.ResourceType.RT_MEDIA,
            )
            assertEquals(expected, admitted(type = type), type.name)
            assertFalse(admitted(type = type, policy = BrowserDocumentResourcePolicy.Isolated))
        }
        assertFalse(admitted(method = "POST"))
        assertFalse(admitted(isNavigation = true))
        assertFalse(admitted(isMainFrame = false))
        assertFalse(admitted(isMainFrame = null))
        assertFalse(admitted(isDownload = true))
        for (url in listOf(
            "file:///etc/passwd",
            "data:text/css,body{}",
            "https://u:p@example.com/x",
            "https://a.invalid/x",
            "https://a b/x",
        )) {
            assertFalse(admitted(url = url), url)
        }
        assertTrue(admitted(url = "http://127.0.0.1:123/theme.css"))
    }

    @Test
    fun `external CSP expands assets while preserving connection and document restrictions`() {
        val isolated = desktopDocumentHeaders.getValue("Content-Security-Policy").directives()
        val external = desktopDocumentHeaders(BrowserDocumentResourcePolicy.ExternalAssets)
            .getValue("Content-Security-Policy").directives()
        val assetDirectives = setOf("script-src", "style-src", "img-src", "font-src", "media-src")
        isolated.forEach { (name, sources) ->
            if (name in assetDirectives) {
                assertTrue(external.getValue(name).contains("https:"))
                assertTrue(external.getValue(name).contains("http:"))
            } else {
                assertEquals(sources, external[name], name)
            }
        }
        assertEquals("'none'", external["connect-src"])
        assertEquals("'none'", external["frame-src"])
        assertEquals("'none'", external["worker-src"])
    }

    private fun admitted(
        policy: BrowserDocumentResourcePolicy = BrowserDocumentResourcePolicy.ExternalAssets,
        url: String = "https://assets.example/theme.css",
        method: String = "GET",
        type: CefRequest.ResourceType = CefRequest.ResourceType.RT_STYLESHEET,
        isNavigation: Boolean = false,
        isMainFrame: Boolean? = true,
        isDownload: Boolean = false,
    ): Boolean = allowsDesktopDocumentAsset(
        policy,
        DesktopDocumentAssetRequest(url, method, type, isNavigation, isMainFrame, isDownload),
    )

    private fun String.directives(): Map<String, String> = split(';').map(String::trim).filter(String::isNotEmpty)
        .associate { it.substringBefore(' ') to it.substringAfter(' ') }
}
