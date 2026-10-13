package io.aequicor.heartbeat.feature.browser.impl.presentation

import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.impl.data.DefaultBrowserUrlResolver
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopBrowserProfiles
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopDefaultBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.browser.CefRequestContext
import org.junit.Assume.assumeTrue
import java.awt.BorderLayout
import java.awt.GridLayout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Opt-in real Chromium check of isolated documents, run locally in its own JVM:
 * `./gradlew :features:browser:impl:browserDocumentNativeTest` (`jvmTest` skips it). Requires a desktop session.
 * The pages report through their own bridge, which this test answers.
 */
class DesktopDocumentNativeTest {
    private val requests = ConcurrentLinkedQueue<String>()

    @Test
    fun `documents keep to their origin without network and talk only through their own bridge`() {
        assumeTrue(System.getProperty("heartbeat.browser.documentNativeTest") == "true")
        val server = localServer()
        val base = "http://127.0.0.1:${server.address.port}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val handle = BrowserTestScope(scope)
        val resources = nativeResources(handle)
        val documents = ProfileBrowserDocuments()
        val reports = LinkedBlockingQueue<Pair<String, JsonElement>>()
        val other = LinkedBlockingQueue<Pair<String, JsonElement>>()
        val first = assertNotNull(documents.open(document(base, JsonPrimitive("initial"), reports)))
        val second = assertNotNull(documents.open(document(base, null, other)))
        val panels = mutableListOf<DesktopDocumentPanel>()
        var tab: DesktopBrowserEngine? = null
        val tabStates = LinkedBlockingQueue<BrowserViewState>()
        val frames = mutableListOf<JFrame>()
        val show = { opened: BrowserDocumentHandle, frame: JFrame ->
            val panel = DesktopDocumentPanel(
                assertNotNull(documents.documents.value[opened.route.id]),
                resources,
                nativeDispatchers(),
                scope,
            ) {}
            panels += panel
            frame.add(panel.createComponent())
            frame.validate()
        }
        try {
            SwingUtilities.invokeAndWait {
                frames += JFrame("Heartbeat document native verification").apply {
                    layout = GridLayout(1, 2)
                    setSize(900, 500)
                    isVisible = true
                }
                show(first, frames.first())
            }
            val origin = "https://${first.route.id}.document.heartbeat.invalid"
            verifyFirstPage(reports, origin)

            // The first page has written its storage; the second starts only now and must not see it.
            SwingUtilities.invokeAndWait { show(second, frames.first()) }
            assertEquals(JsonNull, report(other, "data"))
            assertEquals(JsonNull, report(other, "storage-before"), "Documents share no storage")

            first.update(buildJsonObject { put("count", 2) })
            assertEquals(buildJsonObject { put("count", 2) }, report(reports, "update"))
            assertEquals(JsonPrimitive(origin), report(reports, "after-navigation"), "Navigation must stay blocked")

            verifyUserTab(resources, base, tabStates) { engine, frame ->
                tab = engine
                frames += frame
            }
            assertTrue(reports.none { it.first == "tab" } && other.none { it.first == "tab" })
            assertEquals(emptyList(), requests.toList(), "Documents must not reach the network")
        } finally {
            SwingUtilities.invokeAndWait {
                panels.forEach(DesktopDocumentPanel::release)
                tab?.release()
                frames.forEach(JFrame::dispose)
            }
            close(resources, handle, scope, server, panels, tab)
            listOf(first, second).forEach(BrowserDocumentHandle::close)
        }
    }

    /** The page reads its data at its own origin, has storage, gets bridge failures and no network. */
    private fun verifyFirstPage(reports: LinkedBlockingQueue<Pair<String, JsonElement>>, origin: String) {
        assertEquals(JsonPrimitive("initial"), report(reports, "data"))
        assertEquals(JsonPrimitive(origin), report(reports, "origin"))
        assertEquals(JsonPrimitive("blocked"), report(reports, "fetch"))
        assertEquals(JsonPrimitive("v"), report(reports, "storage"))
        assertEquals(JsonPrimitive("nope"), report(reports, "failure"))
        assertEquals(JsonPrimitive(true), report(reports, "ready"))
    }

    /** A user's tab may see the router function of this run, but its query never reaches a document bridge. */
    private fun verifyUserTab(
        resources: DesktopDefaultBrowserResources,
        base: String,
        states: LinkedBlockingQueue<BrowserViewState>,
        created: (DesktopBrowserEngine, JFrame) -> Unit,
    ) {
        val app = runBlocking { resources.app() }
        SwingUtilities.invokeAndWait {
            val engine = DesktopBrowserEngine(
                app,
                nativeDispatchers(),
                DefaultBrowserUrlResolver(),
                { false },
                states::offer,
                guard = DesktopBrowserAgentGuard(),
                createContext = { handler -> requireNotNull(CefRequestContext.createContext(handler)) },
            )
            val frame = JFrame("Heartbeat user tab").apply {
                add(engine.component, BorderLayout.CENTER)
                setSize(400, 300)
                isVisible = true
            }
            created(engine, frame)
            engine.start("$base/tab")
        }
        val title = awaitTitle(states) { it == "failed" || it == "answered" || it == "undefined" }
        assertTrue(title != "answered", "A tab's query must not be answered")
    }

    private fun document(base: String, data: JsonElement?, reports: LinkedBlockingQueue<Pair<String, JsonElement>>) =
        BrowserDocument(
            html = documentHtml(base),
            title = "Document",
            data = data,
            bridge = { method, arguments ->
                when (method) {
                    "report" -> {
                        val report = arguments as JsonObject
                        reports.offer(report.getValue("name").jsonPrimitive.content to (report["value"] ?: JsonNull))
                        BrowserBridgeResult.Value(JsonNull)
                    }

                    else -> BrowserBridgeResult.Failure("nope")
                }
            },
        )

    private fun documentHtml(base: String) = """
        <!doctype html><html><head><title>Document</title>
        <link rel="dns-prefetch" href="//prefetch.heartbeat.invalid"><link rel="preconnect" href="$base/preconnect">
        </head><body><img src="$base/image"><iframe src="$base/frame"></iframe>
        <script>
        const report = (name, value) => heartbeat.call('report', { name, value });
        const attempt = (action) => {
          try { action(); } catch (error) { return String(error); }
          return 'attempted';
        };
        (async () => {
          await report('data', heartbeat.data);
          await report('origin', location.origin);
          await report('fetch', await fetch('$base/fetch').then(() => 'loaded', () => 'blocked'));
          attempt(() => new WebSocket('${base.replace("http", "ws")}/socket'));
          attempt(() => navigator.sendBeacon('$base/beacon', 'x'));
          await report('storage-before', localStorage.getItem('k'));
          localStorage.setItem('k', 'v');
          await report('storage', localStorage.getItem('k'));
          try { await heartbeat.call('missing', null); } catch (error) { await report('failure', error.message); }
          window.addEventListener('heartbeat:data', async (event) => {
            await report('update', event.detail);
            location.href = '$base/navigation';
            setTimeout(() => report('after-navigation', location.origin), 1000);
          });
          await report('ready', true);
        })();
        </script></body></html>
        """.trimIndent()

    private fun report(reports: LinkedBlockingQueue<Pair<String, JsonElement>>, name: String): JsonElement {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail("Document report '$name' timed out")
            val report = reports.poll(remaining, TimeUnit.NANOSECONDS) ?: fail("Document report '$name' timed out")
            if (report.first == name) return report.second
        }
    }

    private fun awaitTitle(states: LinkedBlockingQueue<BrowserViewState>, accepts: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail("User tab timed out")
            val state = states.poll(remaining, TimeUnit.NANOSECONDS) ?: fail("User tab timed out")
            if (accepts(state.title)) return state.title
        }
    }

    private fun localServer(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            // The user tab's own page and its icon are the only expected requests.
            if (exchange.requestURI.path !in setOf("/tab", "/favicon.ico")) requests += exchange.requestURI.path
            val name = desktopDocumentQueryFunction
            val page = if (exchange.requestURI.path == "/tab") {
                "<title>loading</title><script>const query = window['$name'];" +
                    "document.title = typeof query === 'function' ? 'probing' : 'undefined';" +
                    "if (query) query({ request: JSON.stringify({ method: 'report', args: { name: 'tab' } })," +
                    " onSuccess: () => { document.title = 'answered'; }," +
                    " onFailure: () => { document.title = 'failed'; } });" +
                    "setTimeout(() => { if (document.title === 'probing') document.title = 'failed'; }, 3000);</script>"
            } else {
                "unexpected"
            }
            val bytes = page.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private fun close(
        resources: DesktopDefaultBrowserResources,
        handle: BrowserTestScope,
        scope: CoroutineScope,
        server: HttpServer,
        panels: List<DesktopDocumentPanel>,
        tab: DesktopBrowserEngine?,
    ) {
        try {
            runBlocking {
                withTimeout(60_000) {
                    panels.forEach { it.awaitClosed() }
                    tab?.awaitClosed()
                    resources.shutdown()
                }
            }
        } finally {
            handle.close()
            scope.cancel()
            server.stop(0)
        }
    }

    private fun nativeResources(handle: BrowserTestScope): DesktopDefaultBrowserResources {
        val cache = Files.createTempDirectory("heartbeat-jcef-document-test")
        return DesktopDefaultBrowserResources(
            StorageRoot { cache.toString() },
            nativeDispatchers(),
            DesktopBrowserProfiles(handle, nativeDispatchers(), lazy { BrowserNativeTestStores(cache) }),
            handle,
        )
    }

    private fun nativeDispatchers(): DispatcherProvider = object : DispatcherProvider {
        override val main = Dispatchers.Swing
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
}
