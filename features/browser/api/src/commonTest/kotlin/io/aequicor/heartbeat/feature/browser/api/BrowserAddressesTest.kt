package io.aequicor.heartbeat.feature.browser.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserAddressesTest {
    @Test
    fun `address input preserves URLs and encodes Google queries`() {
        assertEquals("https://example.com", browserAddress("example.com"))
        assertEquals("https://localhost:8080", browserAddress("localhost:8080"))
        assertEquals("https://www.google.com/search?q=hello%20world", browserAddress("hello world"))
        assertEquals(
            "https://www.google.com/search?q=%D1%82%D0%B5%D1%81%D1%82%26x%3D1",
            browserSearchUrl("тест&x=1"),
        )
    }

    @Test
    fun `explicit unsafe schemes do not become searches`() {
        assertNull(browserAddress("javascript:alert(1)"))
        assertNull(browserAddress("file:///secret"))
        assertNull(browserAddress("a\nb"))
    }

    @Test
    fun `queries with dots colons or spaces become searches`() {
        assertEquals(browserSearchUrl("kotlin 2.0 release"), browserAddress("kotlin 2.0 release"))
        assertEquals(browserSearchUrl("Node.js tutorial"), browserAddress("Node.js tutorial"))
        assertEquals(browserSearchUrl("what is 12:30"), browserAddress("what is 12:30"))
        assertEquals(browserSearchUrl("Node.js: tutorial"), browserAddress("Node.js: tutorial"))
        assertEquals(browserSearchUrl("12:30"), browserAddress("12:30"))
        assertEquals(browserSearchUrl("1.2"), browserAddress("1.2"))
    }

    @Test
    fun `hosts ports and explicit schemes stay addresses`() {
        assertEquals("https://example.com", browserAddress(" example.com "))
        assertEquals("https://example.com:8443/a?q=1", browserAddress("example.com:8443/a?q=1"))
        assertEquals("https://localhost", browserAddress("localhost"))
        assertEquals("https://x", browserAddress("https://x"))
        assertEquals("http://127.0.0.1:8080", browserAddress("http://127.0.0.1:8080"))
        assertNull(browserAddress("about:blank"))
        assertNull(browserAddress("data:text/html,hi"))
        assertNull(browserAddress("https://exa mple.com"))
    }
}
