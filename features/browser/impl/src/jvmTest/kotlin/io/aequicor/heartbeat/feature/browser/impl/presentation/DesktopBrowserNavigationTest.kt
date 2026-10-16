package io.aequicor.heartbeat.feature.browser.impl.presentation

import org.cef.browser.CefBrowser
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopBrowserNavigationTest {
    private val states = mutableListOf<BrowserViewState>()
    private val navigation = DesktopBrowserNavigation(browser(), { states += it })

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

    private fun BrowserViewState.phase() = isLoading to isAwaitingResponse

    /** Navigation only issues commands to the native browser; none of them return a value it reads. */
    private fun browser(): CefBrowser =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CefBrowser::class.java)) { _, _, _ -> null } as CefBrowser
}
