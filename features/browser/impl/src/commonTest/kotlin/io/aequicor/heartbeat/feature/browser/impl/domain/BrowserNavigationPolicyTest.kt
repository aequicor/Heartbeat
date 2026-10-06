package io.aequicor.heartbeat.feature.browser.impl.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserNavigationPolicyTest {
    @Test
    fun `main documents allow HTTP and HTTPS but never embedded memory URLs`() {
        listOf("https://example.com/page", "http://localhost:8080/page").forEach { url ->
            BrowserRequestKind.entries.forEach { kind -> assertTrue(isBrowserRequestAllowed(url, kind), "$kind $url") }
        }
        listOf(
            "data:text/html,<p>embedded</p>",
            "blob:https://example.com/id",
            "about:blank",
            "about:srcdoc",
            "ws://example.com/socket",
            "wss://example.com/socket",
        ).forEach { url -> assertFalse(isBrowserRequestAllowed(url, BrowserRequestKind.MainDocument), url) }
    }

    @Test
    fun `only the engine owned blank bypasses main document policy`() {
        assertTrue(isBrowserRequestAllowed("about:blank", BrowserRequestKind.MainDocument, isOwnBlank = true))
        listOf("about:blank#untrusted", "about:srcdoc", "data:text/html,content", "file:///etc/passwd").forEach { url ->
            assertFalse(isBrowserRequestAllowed(url, BrowserRequestKind.MainDocument, isOwnBlank = true), url)
        }
    }

    @Test
    fun `child documents and resources allow memory content under native origin checks`() {
        listOf(
            "data:text/html,<p>embedded</p>",
            "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg'/>",
            "blob:https://example.com/id",
            "blob:http://localhost:8080/id",
            "blob:null/opaque-id",
            "about:blank",
        ).forEach { url ->
            assertTrue(isBrowserRequestAllowed(url, BrowserRequestKind.ChildDocument), url)
            assertTrue(isBrowserRequestAllowed(url, BrowserRequestKind.Subresource), url)
        }
        assertTrue(isBrowserRequestAllowed("about:srcdoc", BrowserRequestKind.ChildDocument))
    }

    @Test
    fun `websockets are allowed only as resources`() {
        listOf("ws://localhost:8080/socket", "wss://example.com/socket").forEach { url ->
            assertTrue(isBrowserRequestAllowed(url, BrowserRequestKind.Subresource), url)
            assertFalse(isBrowserRequestAllowed(url, BrowserRequestKind.ChildDocument), url)
            assertFalse(isBrowserRequestAllowed(url, BrowserRequestKind.MainDocument), url)
        }
        listOf("ws:relative", "wss://user:password@example.com", "wss://").forEach { url ->
            assertFalse(isBrowserRequestAllowed(url, BrowserRequestKind.Subresource), url)
        }
    }

    @Test
    fun `files native protocols and privileged schemes are rejected in every context`() {
        listOf(
            "file:///etc/passwd",
            "FILE:///etc/passwd",
            "content://provider/document",
            "intent://external",
            "mailto:person@example.com",
            "tel:123",
            "javascript:alert(1)",
            "chrome://settings",
            "about:config",
            "ftp://example.com/file",
            "blob:file:///private/id",
            "blob:custom://native/id",
        ).forEach { url ->
            BrowserRequestKind.entries.forEach { kind ->
                assertFalse(isBrowserRequestAllowed(url, kind, isOwnBlank = true), "$kind $url")
            }
        }
    }
}
