package io.aequicor.heartbeat.feature.browser.impl.ui

import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.feature.browser.impl.data.DefaultBrowserUrlResolver
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopBrowserProfiles
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopDefaultBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserNativeTestStores
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestScope
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserViewController
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserViewState
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserEngine
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserPanel
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserPanelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.cef.browser.CefRequestContext
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.assertNull

/** Real production panel/engine; only the profile retention owner is a test fixture. */
internal class NativeComposeBrowserFixture :
    BrowserSurface,
    AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val handle = BrowserTestScope(scope)
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Swing
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val cache = Files.createTempDirectory("heartbeat-compose-jcef")
    private val resources = DesktopDefaultBrowserResources(
        StorageRoot { cache.toString() },
        dispatchers,
        DesktopBrowserProfiles(handle, dispatchers, lazy { BrowserNativeTestStores(cache) }),
        handle,
    )
    val pageLoads = AtomicInteger()
    val runtimeStarts = AtomicInteger()
    val loaded = CompletableFuture<Unit>()
    private val server = createServer()
    override val initialUrl = "http://127.0.0.1:${server.address.port}/fixture"
    val panel = DesktopBrowserPanel(this, scope, dispatchers) { released, changed ->
        val app = resources.app()
        runtimeStarts.incrementAndGet()
        DesktopBrowserEngine(app, dispatchers, DefaultBrowserUrlResolver(), released, changed) { handler ->
            checkNotNull(CefRequestContext.createContext(handler))
        }
    }
    override val nativeResources = object : DesktopBrowserPanelFactory {
        override fun create(surface: BrowserSurface) = panel
        override fun close(surface: BrowserSurface) = panel.release()
        override suspend fun clearData() = error("Not part of the Compose embedding test")
    }

    override fun attached(controller: BrowserViewController) = Unit
    override fun detached(controller: BrowserViewController) = Unit
    override fun changed(controller: BrowserViewController, state: BrowserViewState) {
        if (state.error != null) loaded.completeExceptionally(AssertionError("Native load: ${state.error}"))
        if (state.title == "compose-native-ready" && !state.isLoading) loaded.complete(Unit)
    }

    fun evaluate(expression: String) = runBlocking { withTimeout(30_000) { evaluateDocument(expression) } }

    private suspend fun evaluateDocument(expression: String): JsonElement {
        val response = Json.parseToJsonElement(
            panel.devTools(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("returnByValue", true)
                    put("awaitPromise", true)
                },
            ),
        ).jsonObject
        assertNull(response["exceptionDetails"], "Fixture JavaScript failed: $response")
        return checkNotNull(response["result"]?.jsonObject?.get("value"))
    }

    override fun close() {
        try {
            runBlocking {
                withTimeout(60_000) {
                    panel.awaitClosed()
                    resources.shutdown()
                }
            }
        } finally {
            handle.close()
            scope.cancel()
            server.stop(0)
        }
    }

    private fun createServer(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/fixture") { exchange ->
            pageLoads.incrementAndGet()
            val bytes = """
                <title>compose-native-ready</title>
                <input id="entry" style="position:absolute;left:24px;top:24px;width:180px;height:44px">
                <script>window.retainedMarker = 'unchanged';</script>
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
}

internal fun <T> nativeEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeLater(task)
    return task.get(30, TimeUnit.SECONDS)
}

internal fun <T> CompletableFuture<T>.awaitNative(): T = get(30, TimeUnit.SECONDS)
