package io.aequicor.heartbeat.feature.browser.impl.presentation

import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.impl.data.DefaultBrowserUrlResolver
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopBrowserProfiles
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopDefaultBrowserResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.browser.CefRequestContext
import org.cef.handler.CefRequestContextHandler
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
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Opt-in real Chromium smoke test, run locally in its own JVM: `./gradlew :features:browser:impl:browserNativeTest`
 * (`jvmTest` skips it). Requires a desktop session.
 */
class DesktopBrowserNativeTest {
    private val contexts = mutableListOf<CefRequestContext>()
    private val loopRequests = AtomicInteger()

    @Test
    fun `native surfaces isolate cookies and storage and reopen after close`() {
        assumeTrue(System.getProperty("heartbeat.browser.nativeTest") == "true")
        val server = localServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val handle = BrowserTestScope(scope)
        val resources = nativeResources(handle)
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
            val app = runBlocking { resources.app() }
            verifyStandaloneEngines(app, server)
            SwingUtilities.invokeAndWait {
                frame = nativeFrame()
                first = testEngine(app, firstStates)
                frame?.add(first?.component, BorderLayout.CENTER)
                frame?.validate()
                first?.start("$base/start")
            }
            awaitTitle(firstStates, "cookie-storage")

            SwingUtilities.invokeAndWait {
                frame?.remove(first?.component)
                second = testEngine(app, secondStates)
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
                reopened = testEngine(app, reopenedStates)
                frame?.add(reopened?.component, BorderLayout.CENTER)
                frame?.validate()
                reopened?.start("$base/check")
            }
            awaitTitle(reopenedStates, "empty-empty")
            verifyRejectedNavigation(assertNotNull(reopened), reopenedStates, base)
            verifyEmbeddedContent(assertNotNull(reopened), reopenedStates, base)
            verifyRecovery(assertNotNull(reopened), reopenedStates, base)
            verifyReattachment(assertNotNull(reopened), assertNotNull(frame))
        } finally {
            SwingUtilities.invokeAndWait {
                first?.release()
                second?.release()
                reopened?.release()
                frame?.dispose()
            }
            closeNativeTest(resources, handle, scope, server, listOfNotNull(first, second, reopened))
        }
    }

    private fun verifyStandaloneEngines(app: org.cef.CefApp, server: HttpServer) {
        verifyEarlyClose(app)
        verifyDirectDocument(app, "http://127.0.0.1:${server.address.port}")
        verifyAgentGuard(app, server)
    }

    private fun closeNativeTest(
        resources: DesktopDefaultBrowserResources,
        handle: BrowserTestScope,
        scope: CoroutineScope,
        server: HttpServer,
        engines: List<DesktopBrowserEngine>,
    ) {
        try {
            runBlocking {
                withTimeout(60_000) {
                    engines.forEach { it.awaitClosed() }
                    contexts.forEach { context ->
                        suspendCancellableCoroutine { continuation -> context.dispose { continuation.resume(Unit) } }
                    }
                    resources.shutdown()
                }
            }
        } finally {
            handle.close()
            scope.cancel()
            server.stop(0)
        }
    }

    private fun testEngine(
        app: org.cef.CefApp,
        states: LinkedBlockingQueue<BrowserViewState>,
        guard: DesktopBrowserAgentGuard = DesktopBrowserAgentGuard(),
    ) = DesktopBrowserEngine(
        app,
        nativeDispatchers(),
        DefaultBrowserUrlResolver(),
        { false },
        states::offer,
        guard = guard,
        createContext = temporaryContextFactory(),
    )

    private fun verifyDirectDocument(app: org.cef.CefApp, base: String) {
        var engine: DesktopBrowserEngine? = null
        SwingUtilities.invokeAndWait { engine = testEngine(app, LinkedBlockingQueue()) }
        val runtime = assertNotNull(engine)
        try {
            runBlocking {
                val document = runtime.captureResource("$base/document")
                assertEquals("application/pdf", document.getValue("mediaType").jsonPrimitive.content)
                val redirected = runtime.captureResource("$base/document-redirect")
                assertEquals("$base/document", redirected.getValue("url").jsonPrimitive.content)
                val external = runtime.captureResource("$base/external-redirect")
                assertEquals("application/pdf", external.getValue("mediaType").jsonPrimitive.content)
                assertEquals(
                    "${base.replace("127.0.0.1", "localhost")}/document",
                    external.getValue("url").jsonPrimitive.content,
                )
                assertFailsWith<IllegalStateException> { runtime.captureResource("$base/forbidden-redirect") }
                assertFailsWith<IllegalStateException> { runtime.captureResource("$base/redirect-loop") }
                assertEquals(11, loopRequests.get(), "The eleventh redirect must be rejected before another request")
            }
        } finally {
            SwingUtilities.invokeAndWait(runtime::release)
            runBlocking { runtime.awaitClosed() }
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
        SwingUtilities.invokeAndWait {
            // The preceding rejected navigation can still have queued loading/title projections.
            // Start observation on the EDT together with the new command, which clears that error.
            states.clear()
            engine.execute(BrowserViewCommand.Load("$base/embedded"))
        }
        val loaded = awaitState(states) {
            assertEquals(null, it.error, "A child resource must not fail the main document")
            it.title == "embedded-ready" && !it.isLoading
        }
        assertEquals("$base/embedded", loaded.url)
        assertTrue(loaded.isBackAvailable, "Embedded loads must preserve main document history")
    }

    private fun verifyRecovery(
        engine: DesktopBrowserEngine,
        states: LinkedBlockingQueue<BrowserViewState>,
        base: String,
    ) {
        SwingUtilities.invokeAndWait { engine.execute(BrowserViewCommand.Load("$base/locked")) }
        awaitTitle(states, "Unlock source")
        val driver = DesktopBrowserAutomation(engine::devTools, engine::captureResource)
        fun request(url: String) = BrowserRequest(
            "capture",
            BrowserOperation.CaptureResource,
            "tab",
            arguments = buildJsonObject { put("url", url) },
        )
        runBlocking {
            assertFailsWith<IllegalStateException> {
                driver.captureResource(request("$base/locked"))
            }
            val page = driver.perform(BrowserRequest("read", BrowserOperation.Read, "tab"))
            assertTrue(page.data.getValue("content").jsonPrimitive.content.contains("Unlock"))
            val image =
                driver.perform(BrowserRequest("screenshot", BrowserOperation.Screenshot, "tab"))
            assertTrue(assertNotNull(image.imageBase64).isNotEmpty())
            // A fixture's explicit local button stands in for the user's manual access action.
            val refreshed =
                driver.perform(BrowserRequest("read-again", BrowserOperation.Read, "tab"))
            driver.perform(
                BrowserRequest(
                    "click",
                    BrowserOperation.Click,
                    "tab",
                    arguments =
                        buildJsonObject {
                            put("snapshotId", refreshed.data.getValue("snapshotId"))
                            put("element", 0)
                        },
                ),
            )
        }
        awaitTitle(states, "Recovered source")
        driver.invalidate()
        runBlocking {
            val text = driver.captureResource(request("$base/locked"))
            assertTrue(
                text.data
                    .getValue("content")
                    .jsonPrimitive
                    .content
                    .contains("Recovered research text"),
            )
            val pdf = driver.captureResource(request("$base/document"))
            assertEquals("application/pdf", pdf.data.getValue("mediaType").jsonPrimitive.content)
            val protectedPdf = driver.captureResource(request("$base/protected-document"))
            assertEquals("application/pdf", protectedPdf.data.getValue("mediaType").jsonPrimitive.content)
            assertFailsWith<IllegalStateException> {
                driver.captureResource(request("$base/unsupported"))
            }
        }
    }

    private fun verifyReattachment(engine: DesktopBrowserEngine, frame: JFrame) {
        fun evaluate(expression: String) = runBlocking {
            engine.devTools(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("returnByValue", true)
                },
            )
        }
        evaluate("window.__heartbeatRetainedProof = 'retained'")
        SwingUtilities.invokeAndWait {
            frame.remove(engine.component)
            frame.validate()
        }
        SwingUtilities.invokeAndWait {
            frame.setSize(1200, 800)
            frame.add(engine.component, BorderLayout.CENTER)
            frame.validate()
        }
        assertTrue(
            evaluate("window.__heartbeatRetainedProof").contains("retained"),
            "Reattaching and resizing must preserve the live document",
        )
    }

    private fun verifyEarlyClose(app: org.cef.CefApp) {
        val closed = CountDownLatch(2)
        val callbacks = AtomicInteger()
        SwingUtilities.invokeAndWait {
            val unstarted = DesktopBrowserEngine(
                app,
                nativeDispatchers(),
                DefaultBrowserUrlResolver(),
                { false },
                { callbacks.incrementAndGet() },
                closed::countDown,
                guard = DesktopBrowserAgentGuard(),
                createContext = temporaryContextFactory(),
            )
            unstarted.release()
            unstarted.release()
            val pending = DesktopBrowserEngine(
                app,
                nativeDispatchers(),
                DefaultBrowserUrlResolver(),
                { false },
                { callbacks.incrementAndGet() },
                closed::countDown,
                guard = DesktopBrowserAgentGuard(),
                createContext = temporaryContextFactory(),
            )
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

    private fun nativeResources(handle: BrowserTestScope): DesktopDefaultBrowserResources {
        val cache = Files.createTempDirectory("heartbeat-jcef-native-test")
        return DesktopDefaultBrowserResources(
            StorageRoot { cache.toString() },
            nativeDispatchers(),
            DesktopBrowserProfiles(handle, nativeDispatchers(), lazy { BrowserNativeTestStores(cache) }),
            handle,
        )
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
        recoveryEndpoints(server)
        redirectEndpoints(server)
        server.createContext("/") { exchange ->
            val page = if (exchange.requestURI.path == "/start") {
                exchange.responseHeaders.add(
                    "Set-Cookie",
                    "heartbeat_browser=first; Path=/; SameSite=Lax",
                )
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

    private fun recoveryEndpoints(server: HttpServer) {
        server.createContext("/locked") { exchange ->
            val unlocked =
                exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("source_unlocked=yes")
            val html =
                if (unlocked) {
                    "<title>Recovered source</title>Recovered research text"
                } else {
                    "<title>Unlock source</title><a href='/unlock'>Unlock</a>"
                }
            val bytes = html.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(if (unlocked) 200 else 403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/unlock") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "source_unlocked=yes; Path=/; SameSite=Lax")
            exchange.responseHeaders.add("Location", "/locked")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/document") { exchange ->
            val bytes = "%PDF-1.4\nfixture\n%%EOF".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/protected-document") { exchange ->
            val unlocked = exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("source_unlocked=yes")
            val bytes = if (unlocked) "%PDF-1.4\nprotected\n%%EOF".toByteArray() else "Blocked".toByteArray()
            exchange.responseHeaders.add("Content-Type", if (unlocked) "application/pdf" else "text/html")
            exchange.sendResponseHeaders(if (unlocked) 200 else 403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/unsupported") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.use { it.write(byteArrayOf(0)) }
        }
    }

    private fun redirectEndpoints(server: HttpServer) {
        server.createContext("/document-redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/document")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/external-redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/document")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/forbidden-redirect") { exchange ->
            exchange.responseHeaders.add("Location", "file:///heartbeat-forbidden-document")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/redirect-loop") { exchange ->
            loopRequests.incrementAndGet()
            exchange.responseHeaders.add("Location", "/redirect-loop")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
    }

    // Each engine represents an isolated profile. Its tab and managed requests share an anchor;
    // restart durability is exercised separately by the native multi-process probe.
    private fun temporaryContextFactory(): (CefRequestContextHandler) -> CefRequestContext {
        val anchor = requireNotNull(CefRequestContext.createContext(null))
        contexts += anchor
        return { handler -> requireNotNull(CefRequestContext.createContext(anchor, handler)) }
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
            val state = assertNotNull(
                states.poll(remaining, TimeUnit.NANOSECONDS),
                "Browser callback timed out",
            )
            assertTrue(state.error != BrowserViewError.EngineUnavailable, "Browser runtime failed")
            if (predicate(state)) return state
        }
    }

    /**
     * An agent tab approved for the page's own loopback origin must not load an iframe from another local origin.
     * Raw listeners count request lines; the unrestricted run proves the page makes the request. The page also opens a
     * WebSocket to show the guard's documented limit: CEF never passes the handshake to request handlers, so it reaches
     * the listener in both runs.
     */
    private fun verifyAgentGuard(app: org.cef.CefApp, server: HttpServer) {
        val base = "http://127.0.0.1:${server.address.port}"
        val (openSockets, openFrames) = guardProbe(app, server, DesktopBrowserAgentGuard())
        assertTrue(openSockets > 0, "Control: an unrestricted page must open the WebSocket")
        assertTrue(openFrames > 0, "Control: an unrestricted page must load the iframe")
        val guard = DesktopBrowserAgentGuard().apply { restrict(base) }
        val (guardedSockets, guardedFrames) = guardProbe(app, server, guard)
        assertEquals(0, guardedFrames, "An agent tab must not load an iframe from another local origin")
        assertTrue(guard.consumeContentRefusal(), "The refused iframe must be reported as blocked content")
        assertTrue(guardedSockets > 0, "CEF now routes WebSocket handshakes: extend the agent guard to sockets")
    }

    private fun guardProbe(app: org.cef.CefApp, server: HttpServer, guard: DesktopBrowserAgentGuard): Pair<Int, Int> {
        val base = "http://127.0.0.1:${server.address.port}"
        ConnectionCounter().use { sockets ->
            ConnectionCounter().use { frames ->
                val path = "/guard-probe-${sockets.port}-${frames.port}"
                server.createContext(path) { exchange ->
                    val bytes = guardProbePage(sockets.port, frames.port).toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                val states = LinkedBlockingQueue<BrowserViewState>()
                var engine: DesktopBrowserEngine? = null
                var frame: JFrame? = null
                try {
                    SwingUtilities.invokeAndWait {
                        frame = nativeFrame()
                        engine = testEngine(app, states, guard)
                        frame?.add(engine?.component, BorderLayout.CENTER)
                        frame?.validate()
                        engine?.start("$base$path")
                    }
                    awaitTitle(states, "guard-probe-done")
                    // Late handshakes still count: give the network service time to dial before reading.
                    Thread.sleep(GUARD_SETTLE_MILLIS)
                    return sockets.requests.get() to frames.requests.get()
                } finally {
                    SwingUtilities.invokeAndWait {
                        engine?.release()
                        frame?.dispose()
                    }
                    runBlocking { engine?.awaitClosed() }
                    server.removeContext(path)
                }
            }
        }
    }

    private fun guardProbePage(socketPort: Int, framePort: Int): String = listOf(
        "<title>guard-probe</title>",
        "<iframe src=\"http://127.0.0.1:$framePort/frame\"></iframe>",
        "<script>",
        "try { new WebSocket('ws://127.0.0.1:$socketPort/socket'); } catch (e) {}",
        "setTimeout(function () { document.title = 'guard-probe-done'; }, 1500);",
        "</script>",
    ).joinToString("\n")

    /** Counts HTTP requests (a request line, not a bare preconnect) to a loopback port without answering them. */
    private class ConnectionCounter : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val requests = AtomicInteger()
        private val log = Log.tag("NativeProbeListener")
        private val acceptor = Thread {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (closed: java.net.SocketException) {
                    log.w(closed) { "Probe listener closed" }
                    break
                }
                Thread { readRequestLine(client) }.apply {
                    isDaemon = true
                    start()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        private fun readRequestLine(client: java.net.Socket) = client.use {
            it.soTimeout = 5_000
            val line = try {
                it.getInputStream().bufferedReader().readLine()
            } catch (unanswered: java.io.IOException) {
                log.w(unanswered) { "Probe connection sent no request line" }
                null
            }
            if (line?.startsWith("GET ") == true) requests.incrementAndGet()
        }

        override fun close() {
            socket.close()
            acceptor.join(1_000)
        }
    }

    private companion object {
        const val GUARD_SETTLE_MILLIS = 1_000L
    }
}
