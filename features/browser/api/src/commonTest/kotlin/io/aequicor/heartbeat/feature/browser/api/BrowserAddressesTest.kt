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
}
