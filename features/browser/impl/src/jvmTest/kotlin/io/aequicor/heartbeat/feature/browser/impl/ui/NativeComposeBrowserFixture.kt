package io.aequicor.heartbeat.feature.browser.impl.ui

import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.browser.impl.data.DefaultBrowserUrlResolver
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopBrowserProfiles
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopDefaultBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.data.bundledBrowserRuntimeInstaller
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserNativeTestStores
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestScope
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserViewController
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserViewState
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserAgentGuard
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.cef.browser.CefBrowser
import org.cef.browser.CefRequestContext
import java.awt.Rectangle
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.assertNull

/** Real production panel/engine; only the profile retention owner is a test fixture. */
internal class NativeComposeBrowserFixture(isViewportProbe: Boolean = false, isAttachmentHeld: Boolean = false) :
    BrowserSurface,
    AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val handle = BrowserTestScope(scope)
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Swing
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }

    /**
     * Storage root under `build/`, fresh per run: Gradle Delete tasks clear it before and after
     * each opt-in Compose native test. JCEF natives unpacked here stay mapped until the JVM exits (Windows locks
     * them), so the fixture never deletes it itself.
     */
    private val cache = Files.createDirectories(
        Path.of(requireNotNull(System.getProperty(NATIVE_ROOT_PROPERTY)) { "$NATIVE_ROOT_PROPERTY is not set" }),
    )
    val pageLoads = AtomicInteger()
    val runtimeStarts = AtomicInteger()
    val loaded = CompletableFuture<Unit>()
    val nativeClippingVerified = CompletableFuture<Unit>()
    private val browser = CompletableFuture<CefBrowser>()
    private val viewportRuntime = CompletableFuture<NativeViewportBrowserRuntime>()
    private val resources: DesktopDefaultBrowserResources
    private val server: HttpServer
    override val initialUrl: String
    val panel: DesktopBrowserPanel

    init {
        var started: HttpServer? = null
        try {
            resources = DesktopDefaultBrowserResources(
                bundledBrowserRuntimeInstaller(cache, dispatchers),
                dispatchers,
                DesktopBrowserProfiles(handle, dispatchers, lazy { BrowserNativeTestStores(cache) }),
                handle,
            )
            val created = createServer()
            started = created
            server = created
            initialUrl = "http://127.0.0.1:${created.address.port}/fixture"
            panel = DesktopBrowserPanel(this, scope, dispatchers) { released, changed ->
                val app = resources.app()
                runtimeStarts.incrementAndGet()
                if (isViewportProbe) {
                    NativeViewportBrowserRuntime(app, changed, nativeClippingVerified, isAttachmentHeld).also {
                        browser.complete(it.browser)
                        viewportRuntime.complete(it)
                    }
                } else {
                    DesktopBrowserEngine(
                        app,
                        dispatchers,
                        DefaultBrowserUrlResolver(),
                        released,
                        changed,
                        guard = DesktopBrowserAgentGuard(),
                    ) { handler ->
                        checkNotNull(CefRequestContext.createContext(handler))
                    }
                }
            }
        } catch (failure: Throwable) {
            // Nothing native has started yet: release the server and scopes; Gradle clears the storage root.
            runCleanup(
                { started?.stop(0) },
                handle::close,
                { scope.cancel() },
            )?.let(failure::addSuppressed)
            throw failure
        }
    }
    override val nativeResources = object : DesktopBrowserPanelFactory {
        override fun create(
            surface: BrowserSurface,
            guard: DesktopBrowserAgentGuard,
            openTab: ((BrowserCommand.Load) -> Unit)?,
        ) = panel
        override fun close(surface: BrowserSurface) = panel.release()
        override suspend fun clearData() = error("Not part of the Compose embedding test")
    }

    override fun attached(controller: BrowserViewController) = Unit
    override fun detached(controller: BrowserViewController) = Unit
    override fun changed(controller: BrowserViewController, state: BrowserViewState) {
        if (state.error != null) loaded.completeExceptionally(AssertionError("Native load: ${state.error}"))
        if (state.title == "compose-native-ready" && !state.isLoading) loaded.complete(Unit)
    }

    fun evaluate(expression: String) = runBlocking {
        withTimeoutOrNull(30_000) { evaluateDocument(expression) }
            ?: throw AssertionError("Native DOM evaluation timed out: $expression")
    }

    /** Latest public JCEF UpdateUI content rectangle, copied on EDT; no native pointers or reflection. */
    fun nativeContentBounds(): Rectangle {
        val created = browser.awaitNative()
        return nativeEdt { Rectangle(created.windowHandler.getRect(created)) }
    }

    fun viewportRuntime(): NativeViewportBrowserRuntime = viewportRuntime.awaitNative()

    fun verifyDocumentPresentation() {
        verifyNativeDocumentPresentation(resources, dispatchers, scope)
        verifyNativeDocumentWorkspace(resources, dispatchers, scope)
        verifyNativeHiddenDocumentPreview(resources, dispatchers, scope)
    }

    /** A second real browser in the same native runtime, so an existing sibling cannot prove target attachment. */
    fun createViewportSibling(): NativeViewportSibling {
        val app = runBlocking { withTimeout(30_000) { resources.app() } }
        return nativeEdt {
            val loaded = CompletableFuture<Unit>()
            val verified = CompletableFuture<Unit>()
            val runtime = NativeViewportBrowserRuntime(app, { state ->
                if (!state.isLoading) loaded.complete(Unit)
            }, verified)
            runtime.start(initialUrl)
            NativeViewportSibling(runtime, loaded, verified)
        }
    }

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
        runCleanup(
            {
                runBlocking {
                    withTimeout(60_000) {
                        panel.awaitClosed()
                        resources.shutdown()
                    }
                }
            },
            handle::close,
            { scope.cancel() },
            { server.stop(0) },
        )?.let { throw it }
    }

    private fun createServer(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/fixture") { exchange ->
            pageLoads.incrementAndGet()
            val bytes = """
                <title>compose-native-ready</title>
                <input id="entry" style="position:absolute;left:24px;top:24px;width:180px;height:44px">
                <script>
                    window.retainedMarker = 'unchanged';
                    window.pageClicks = 0;
                    document.addEventListener('click', () => window.pageClicks++);
                </script>
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private companion object {
        const val NATIVE_ROOT_PROPERTY = "heartbeat.browser.composeNativeRoot"
    }
}

internal class NativeViewportSibling(
    val runtime: NativeViewportBrowserRuntime,
    val loaded: CompletableFuture<Unit>,
    val clippingVerified: CompletableFuture<Unit>,
) : AutoCloseable {
    override fun close() {
        nativeEdt(runtime::release)
        runBlocking { withTimeout(30_000) { runtime.awaitClosed() } }
    }
}

/** Runs every cleanup step even when earlier ones fail; returns the first failure with the others suppressed. */
internal fun runCleanup(vararg steps: () -> Unit): Throwable? {
    var failure: Throwable? = null
    steps.forEach { step ->
        val error = runCatching(step).exceptionOrNull() ?: return@forEach
        val first = failure
        if (first == null) failure = error else first.addSuppressed(error)
    }
    return failure
}

internal fun <T> nativeEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeLater(task)
    return task.get(30, TimeUnit.SECONDS)
}

internal fun <T> CompletableFuture<T>.awaitNative(): T = get(30, TimeUnit.SECONDS)
