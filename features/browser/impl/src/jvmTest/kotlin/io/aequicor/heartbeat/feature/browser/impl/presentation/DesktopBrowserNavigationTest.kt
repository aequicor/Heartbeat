package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserSecurity
import org.cef.browser.CefBrowser
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesktopBrowserNavigationTest {
    private val states = mutableListOf<BrowserViewState>()
    private val navigation = DesktopBrowserNavigation(browser(), { states += it })

    @Test
    fun `cancelled reload retains native certificate warning until a new document commits`() {
        navigation.failed("https://example.com/", "ERR_CERT_AUTHORITY_INVALID")
        navigation.execute(BrowserViewCommand.Reload)
        navigation.execute(BrowserViewCommand.Stop)
        assertEquals(null, states.last().failure)
        assertEquals(true, states.last().isCertificateWarningActive)
        navigation.started("https://example.com/")
        assertEquals(false, states.last().isCertificateWarningActive)
    }

    @Test
    fun `a typed load awaits the response until the main frame starts loading`() {
        navigation.execute(BrowserViewCommand.Load("https://example.com/"))
        assertEquals(true to true, states.last().phase())
        navigation.loading(isLoading = true, isBackAvailable = false, isForwardAvailable = false)
        assertEquals(true to true, states.last().phase())
        navigation.started()
        assertEquals(true to false, states.last().phase())
        navigation.loading(isLoading = false, isBackAvailable = true, isForwardAvailable = false)
        assertEquals(false to false, states.last().phase())
    }

    @Test
    fun `a load the page starts awaits its response and failures or stops end the wait`() {
        navigation.loading(isLoading = true, isBackAvailable = true, isForwardAvailable = false)
        assertEquals(true to true, states.last().phase())
        navigation.failed()
        assertEquals(false to false, states.last().phase())
        navigation.execute(BrowserViewCommand.Load("https://example.com/"))
        navigation.execute(BrowserViewCommand.Stop)
        assertEquals(false to false, states.last().phase())
    }

    @Test
    fun `failed redirect keeps exact URL and error through native error document commit`() {
        navigation.execute(BrowserViewCommand.Load("https://example.com/start"))
        navigation.failed("https://example.org/failed", "ERR_CERT_AUTHORITY_INVALID")
        val failure = states.last().failure
        navigation.started("chrome-error://chromewebdata/")
        navigation.address("https://example.org/failed")
        navigation.loading(false, true, false)
        assertEquals(BrowserSecurity.Unknown, states.last().security)
        assertEquals(failure, states.last().failure)
        assertEquals("https://example.org/failed", states.last().url)
        navigation.execute(BrowserViewCommand.Reload)
        assertEquals(null, states.last().failure)
        navigation.failed("https://example.org/failed", "ERR_CERT_AUTHORITY_INVALID")
        assertNotEquals(failure?.id, states.last().failure?.id)
    }

    @Test
    fun `indicator keeps the committed page until the next commit and drops it on failure`() {
        navigation.execute(BrowserViewCommand.Load("http://example.com/"))
        navigation.address("http://example.com/")
        navigation.loading(isLoading = false, isBackAvailable = false, isForwardAvailable = false)
        assertEquals(BrowserSecurity.Http, states.last().security)
        navigation.execute(BrowserViewCommand.Load("https://example.org/"))
        navigation.loading(isLoading = true, isBackAvailable = true, isForwardAvailable = false)
        assertEquals(BrowserSecurity.Http, states.last().security)
        navigation.execute(BrowserViewCommand.Stop)
        navigation.loading(isLoading = false, isBackAvailable = true, isForwardAvailable = false)
        assertEquals(BrowserSecurity.Http, states.last().security)
        navigation.execute(BrowserViewCommand.Load("https://example.org/"))
        navigation.address("https://example.org/")
        assertEquals(BrowserSecurity.Https, states.last().security)
        navigation.failed("https://example.org/next", "ERR_CONNECTION_RESET", isSubmission = true)
        assertEquals(BrowserSecurity.Unknown, states.last().security)
        assertEquals(true, states.last().failure?.isSubmission)
        assertEquals(false, states.last().failure?.isNetworkRetryable)
    }

    private fun BrowserViewState.phase() = isLoading to isAwaitingResponse

    /** Navigation only issues commands to the native browser; none of them return a value it reads. */
    private fun browser(): CefBrowser =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CefBrowser::class.java)) { _, _, _ -> null } as CefBrowser
}
