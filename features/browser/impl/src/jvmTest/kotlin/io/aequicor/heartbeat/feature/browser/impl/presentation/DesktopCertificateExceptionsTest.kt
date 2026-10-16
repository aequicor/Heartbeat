package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserSecurity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCertificateExceptionsTest {
    @Test
    fun `native profile restores host labels and malformed preference fails closed`() {
        val exceptions = DesktopCertificateExceptions()
        assertFalse(exceptions.restore(null))
        assertTrue(exceptions.restore(mapOf("https://example.com:443,*" to emptyMap<String, Any>())))
        assertTrue(exceptions.contains("https://example.com:8443/"))
        assertFalse(exceptions.contains(""))
        assertFalse(exceptions.contains("http://example.com/"))
    }

    @Test
    fun `native certificate exceptions follow host across ports but not another host`() {
        val exceptions = DesktopCertificateExceptions()
        exceptions.remember("https://example.com:443/path")
        assertTrue(exceptions.contains("https://EXAMPLE.com:8443/other"))
        assertFalse(exceptions.contains("https://other.example.com/"))
        assertFalse(exceptions.contains("http://example.com/"))
        assertEquals(BrowserSecurity.CertificateException, exceptions.security("https://example.com:8443/"))
        exceptions.clear()
        assertFalse(exceptions.contains("https://example.com/"))
    }

    @Test
    fun `agent navigation cannot borrow a human certificate exception`() {
        val exceptions = DesktopCertificateExceptions()
        val guard = DesktopBrowserAgentGuard()
        val policy = DesktopBrowserPolicy(
            isReleased = { false },
            isBootstrap = { false },
            unsupported = {},
            open = {},
            unavailable = {},
            guard = guard,
            certificateExceptions = exceptions,
        )
        exceptions.remember("https://example.com/")
        guard.restrict(null)
        assertFalse(
            policy.allows(
                "https://example.com:8443/",
                io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind.MainDocument,
            ),
        )
        guard.unrestrict()
        assertTrue(
            policy.allows(
                "https://example.com/",
                io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind.MainDocument,
            ),
        )
    }
}
