package io.aequicor.heartbeat.feature.browser.impl.presentation

import org.cef.browser.CefBrowser
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopBrowserRestrictionsTest {
    private val browser = proxy<CefBrowser> { _ -> null }

    @Test
    fun `download target callback is never continued and is held until the download is cancelled`() {
        val blocker = DesktopBrowserDownloadBlocker()
        val continued = mutableListOf<String>()
        val target = CefBeforeDownloadCallback { path, _ -> continued += path }
        var cancels = 0
        val control = object : CefDownloadItemCallback {
            override fun cancel() {
                cancels++
            }

            override fun pause() = Unit

            override fun resume() = Unit
        }

        assertTrue(blocker.onBeforeDownload(browser, item(id = 3), "report.pdf", target))
        assertEquals(1, blocker.heldCount)
        blocker.onDownloadUpdated(browser, item(id = 3), control)
        assertEquals(1, cancels)
        assertEquals(1, blocker.heldCount, "Releasing before cancellation lets the finalizer save the file")
        blocker.onDownloadUpdated(browser, item(id = 3, isCanceled = true), control)
        assertEquals(0, blocker.heldCount)
        assertEquals(1, cancels)
        assertTrue(continued.isEmpty())
    }

    private fun item(id: Int, isCanceled: Boolean = false): CefDownloadItem = proxy { method ->
        when (method) {
            "getId" -> id
            "isCanceled" -> isCanceled
            "isComplete" -> false
            "isInProgress" -> !isCanceled
            else -> null
        }
    }

    private inline fun <reified T : Any> proxy(crossinline answer: (String) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> answer(method.name) } as T
}
