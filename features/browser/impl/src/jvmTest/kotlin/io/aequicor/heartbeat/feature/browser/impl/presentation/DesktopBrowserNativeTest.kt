package io.aequicor.heartbeat.feature.browser.impl.presentation

import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopDefaultBrowserResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.Assume.assumeTrue
import java.awt.BorderLayout
import java.net.CookieHandler
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Opt-in real Chromium smoke test: -Pheartbeat.browser.nativeTest=true --tests '*DesktopBrowserNativeTest'. */
class DesktopBrowserNativeTest {
    @Test
    fun `native surfaces isolate cookies and storage and reopen after close`() {
        assumeTrue(System.getProperty("heartbeat.browser.nativeTest") == "true")
        val server = localServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val handle = BrowserTestScope(scope)
        val cookieHandler = CookieHandler.getDefault()
        val firstStates = LinkedBlockingQueue<BrowserViewState>()
        val secondStates = LinkedBlockingQueue<BrowserViewState>()
        val reopenedStates = LinkedBlockingQueue<BrowserViewState>()
        var first: DesktopBrowserEngine? = null
        var second: DesktopBrowserEngine? = null
        var reopened: DesktopBrowserEngine? = null
        var frame: JFrame? = null
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val app = nativeApp(handle)
            verifyEarlyClose(app)
            SwingUtilities.invokeAndWait {
                frame = nativeFrame()
                first = DesktopBrowserEngine(app, { false }, firstStates::offer)
                frame?.add(first?.component, BorderLayout.CENTER)
                frame?.validate()
                first?.start("$base/start")
            }
            awaitTitle(firstStates, "cookie-storage")

            SwingUtilities.invokeAndWait {
                frame?.remove(first?.component)
                second = DesktopBrowserEngine(app, { false }, secondStates::offer)
                frame?.add(second?.component, BorderLayout.CENTER)
                frame?.validate()
                second?.start("$base/check")
            }
            awaitTitle(secondStates, "empty-empty")
            assertSame(cookieHandler, CookieHandler.getDefault())
            SwingUtilities.invokeAndWait {
                first?.release()
                second?.release()
                frame?.remove(second?.component)
                reopened = DesktopBrowserEngine(app, { false }, reopenedStates::offer)
                frame?.add(reopened?.component, BorderLayout.CENTER)
                frame?.validate()
                reopened?.start("$base/check")
            }
            awaitTitle(reopenedStates, "empty-empty")
            verifyRejectedNavigation(assertNotNull(reopened), reopenedStates, base)
            verifyEmbeddedContent(assertNotNull(reopened), reopenedStates, base)
        } finally {
            SwingUtilities.invokeAndWait {
                first?.release()
                second?.release()
                reopened?.release()
                frame?.dispose()
            }
            handle.close()
            scope.cancel()
            server.stop(0)
        }
    }

    private fun verifyRejectedNavigation(
        engine: DesktopBrowserEngine,
        states: LinkedBlockingQueue<BrowserViewState>,
        base: String,
    ) {
        SwingUtilities.invokeAndWait { engine.execute(BrowserViewCommand.Load("file:///etc/passwd")) }
        val rejected = awaitState(states) { it.error == BrowserViewError.UnsupportedAddress }
        assertEquals("$base/check", rejected.url)
        assertTrue(rejected.title.isNotEmpty())
    }

    private fun verifyEmbeddedContent(
        engine: DesktopBrowserEngine,
        states: LinkedBlockingQueue<BrowserViewState>,
        base: String,
    ) {
        SwingUtilities.invokeAndWait { engine.execute(BrowserViewCommand.Load("$base/embedded")) }
        val loaded = awaitState(states) {
            assertEquals(null, it.error, "A child resource must not fail the main document")
            it.title == "embedded-ready" && !it.isLoading
        }
        assertEquals("$base/embedded", loaded.url)
        assertTrue(loaded.isBackAvailable, "Embedded loads must preserve main document history")
    }

    private fun verifyEarlyClose(app: org.cef.CefApp) {
        val closed = CountDownLatch(2)
        val callbacks = AtomicInteger()
        SwingUtilities.invokeAndWait {
            val unstarted = DesktopBrowserEngine(app, { false }, { callbacks.incrementAndGet() }, closed::countDown)
            unstarted.release()
            unstarted.release()
            val pending = DesktopBrowserEngine(app, { false }, { callbacks.incrementAndGet() }, closed::countDown)
            pending.start("")
            // createImmediately is queued on EDT; release runs before its native creation.
            pending.release()
        }
        assertTrue(closed.await(30, TimeUnit.SECONDS), "Closing pending native creation timed out")
        assertEquals(0, callbacks.get(), "Disposed browser published a stale callback")
    }

    private fun nativeFrame(): JFrame = JFrame("Heartbeat browser native verification").apply {
        layout = BorderLayout()
        setSize(800, 600)
        isVisible = true
    }

    private fun nativeApp(handle: BrowserTestScope): org.cef.CefApp {
        val cache = Files.createTempDirectory("heartbeat-jcef-native-test")
        val resources = DesktopDefaultBrowserResources(StorageRoot { cache.toString() }, nativeDispatchers(), handle)
        return runBlocking { resources.app() }
    }

    private fun localServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/embedded") { exchange ->
            val bytes = browserEmbeddedContentFixture().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/blocked-resource") { exchange ->
            exchange.responseHeaders.add("Location", "file:///heartbeat-browser-must-not-load")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/") { exchange ->
            val page = if (exchange.requestURI.path == "/start") {
                exchange.responseHeaders.add("Set-Cookie", "heartbeat_browser=first; Path=/; SameSite=Lax")
                """<script>localStorage.setItem('marker','first');location.href='/check';</script>"""
            } else {
                """<script>document.title=(document.cookie.includes('heartbeat_browser=first')?'cookie':'empty')+
                    '-'+(localStorage.getItem('marker')==='first'?'storage':'empty');</script>"""
            }
            val bytes = page.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun nativeDispatchers(): DispatcherProvider = object : DispatcherProvider {
        override val main = Dispatchers.Swing
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }

    private fun awaitTitle(states: LinkedBlockingQueue<BrowserViewState>, title: String) {
        awaitState(states) { it.title == title && !it.isLoading }
    }

    private fun awaitState(
        states: LinkedBlockingQueue<BrowserViewState>,
        predicate: (BrowserViewState) -> Boolean,
    ): BrowserViewState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            val remaining = deadline - System.nanoTime()
            assertTrue(remaining > 0, "Browser callback timed out")
            val state = assertNotNull(states.poll(remaining, TimeUnit.NANOSECONDS), "Browser callback timed out")
            assertTrue(state.error != BrowserViewError.EngineUnavailable, "Browser runtime failed")
            if (predicate(state)) return state
        }
    }
}
