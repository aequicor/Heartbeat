package io.aequicor.heartbeat.feature.browser.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserUrlsTest {
    @Test
    fun `bare host defaults to HTTPS preserving path query and fragment`() {
        assertEquals("https://example.com/a?q=value#section", normalizeBrowserUrl("example.com/a?q=value#section"))
        assertEquals("https://localhost:8080", normalizeBrowserUrl("localhost:8080"))
        assertEquals(
            "https://example.com/?redirect=http://other.example",
            normalizeBrowserUrl("example.com/?redirect=http://other.example"),
        )
        assertEquals("https://127.0.0.1:8443/a", normalizeBrowserUrl("127.0.0.1:8443/a"))
        assertEquals("https://xn--e1afmkfd.xn--p1ai", normalizeBrowserUrl("xn--e1afmkfd.xn--p1ai"))
    }

    @Test
    fun `explicit HTTP and HTTPS keep their scheme`() {
        assertEquals("http://localhost:8080/a", normalizeBrowserUrl("HTTP://localhost:8080/a"))
        assertEquals("https://Example.com", normalizeBrowserUrl("HTTPS://Example.com"))
        assertTrue(isBrowserUrlAllowed("HTTP://example.com"))
        assertFalse(isBrowserUrlAllowed("example.com"))
        assertFalse(isBrowserUrlAllowed(""))
        assertFalse(isBrowserUrlAllowed("about:blank"))
    }

    @Test
    fun `explicit IPv6 literals accept compressed full and IPv4 mapped forms`() {
        listOf(
            "http://[::1]:8080/a",
            "https://[2001:db8::1]",
            "http://[1:2:3:4:5:6:7:8]",
            "http://[::ffff:127.0.0.1]",
        ).forEach { assertEquals(it, normalizeBrowserUrl(it)) }
        assertNull(normalizeBrowserUrl("[::1]:8080"))
    }

    @Test
    fun `credentials controls whitespace and backslash are rejected everywhere`() {
        listOf(
            "https://user:pass@example.com", "https://user@example.com", "https://user%40example.com",
            " example.com", "example.com ", "https://example.com/a b", "https://example.com/\n",
            "https://example.com/\t", "https://example.com/\u0000", "https://example.com/\u007f",
            "https://example.com/\u0085", "https://example.com\\evil",
        ).forEach { assertNull(normalizeBrowserUrl(it), it) }
    }

    @Test
    fun `unsupported schemes cannot become hostnames even with numeric payloads`() {
        listOf(
            "javascript:alert(1)", "javascript:443", "file:443", "data:443", "file:///tmp/page",
            "data:text/html,hello", "about:blank", "ftp://example.com", "https:example.com", "//example.com",
        ).forEach {
            assertNull(normalizeBrowserUrl(it), it)
            assertFalse(isBrowserUrlAllowed(it), it)
        }
    }

    @Test
    fun `malformed authorities ports and IPv4 addresses are rejected`() {
        listOf(
            "", "https://", "https:///a", "https://?a", "https://#a", "https://.example.com",
            "https://a..b", "https://-a.com", "https://a-.com", "https://a_b.com",
            "https://example.com:", "https://example.com:0", "https://example.com:65536",
            "https://example.com:-1", "https://example.com:abc", "https://example.com:80:90",
            "https://example.com:999999999999", "https://256.1.1.1", "https://127.1",
            "https://0177.0.0.1", "https://example.com%2fevil",
        ).forEach { assertNull(normalizeBrowserUrl(it), it) }
    }

    @Test
    fun `malformed IPv6 literals and suffixes are rejected`() {
        listOf(
            "http://[::1", "http://::1", "http://[1:2:3]", "http://[1:2:3:4:5:6:7:8:9]",
            "http://[1::2::3]", "http://[:::1]", "http://[gg::1]", "http://[::1]evil",
            "http://[::1]:", "http://[fe80::1%25en0]", "http://[127.0.0.1::]",
            "http://[::ffff:256.0.0.1]", "http://[::1]:65536",
        ).forEach { assertNull(normalizeBrowserUrl(it), it) }
    }
}
