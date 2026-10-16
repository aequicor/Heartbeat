package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserSecurity
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind
import org.cef.browser.CefBrowser
import org.cef.callback.CefCallback
import org.cef.handler.CefLoadHandler
import java.lang.reflect.Proxy
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
    fun `warned hosts and unreadable decisions are withheld from agents without a red label`() {
        val exceptions = DesktopCertificateExceptions()
        exceptions.warned("https://example.com/")
        assertTrue(exceptions.isWithheldFromAgents("https://example.com:8443/"))
        assertFalse(exceptions.isWithheldFromAgents("https://other.example/"))
        assertFalse(exceptions.isWithheldFromAgents("http://example.com/"))
        assertEquals(BrowserSecurity.Https, exceptions.security("https://example.com/"))
        assertFalse(exceptions.restore(null))
        assertTrue(exceptions.isWithheldFromAgents("https://other.example/"))
        assertTrue(exceptions.restore(emptyMap<String, Any>()))
        assertFalse(exceptions.isWithheldFromAgents("https://other.example/"))
        exceptions.clear()
        assertFalse(exceptions.isWithheldFromAgents("https://example.com/"))
    }

    @Test
    fun `a user interstitial withholds its host while an agent tab cancels its own`() {
        val exceptions = DesktopCertificateExceptions()
        val guard = DesktopBrowserAgentGuard()
        val policy = DesktopBrowserPolicy({ false }, { false }, {}, {}, {}, guard, certificateExceptions = exceptions)
        var cancels = 0
        val callback = proxy<CefCallback> { name -> if (name == "cancel") cancels++ }
        val browser = proxy<CefBrowser> {}

        val isHandled = policy.requestHandler.onCertificateError(
            browser,
            CefLoadHandler.ErrorCode.ERR_CERT_DATE_INVALID,
            "https://user.example/",
            callback,
        )
        assertFalse(isHandled)
        assertEquals(0, cancels)
        assertTrue(exceptions.isWithheldFromAgents("https://user.example/"))

        guard.restrict(null)
        assertTrue(
            policy.requestHandler.onCertificateError(
                browser,
                CefLoadHandler.ErrorCode.ERR_CERT_DATE_INVALID,
                "https://agent.example/",
                callback,
            ),
        )
        assertEquals(1, cancels)
        assertFalse(exceptions.isWithheldFromAgents("https://agent.example/"))
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
        assertFalse(policy.allows("https://example.com:8443/", BrowserRequestKind.MainDocument))
        guard.unrestrict()
        assertTrue(policy.allows("https://example.com/", BrowserRequestKind.MainDocument))
        exceptions.warned("https://warned.example/")
        guard.restrict(null)
        assertFalse(policy.allows("https://warned.example/", BrowserRequestKind.MainDocument))
        // Frameless agent requests such as site icons are held to the same rule.
        assertTrue(policy.withholds("https://example.com/favicon.ico"))
        assertFalse(policy.withholds("https://public.example/favicon.ico"))
        guard.unrestrict()
        assertFalse(policy.withholds("https://example.com/favicon.ico"))
    }
}

private inline fun <reified T> proxy(crossinline onCall: (String) -> Unit): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        onCall(method.name)
        null
    } as T
