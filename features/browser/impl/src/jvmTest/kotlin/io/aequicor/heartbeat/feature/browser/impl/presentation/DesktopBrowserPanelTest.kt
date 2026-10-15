package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimeFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimePreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserPanelTest {
    @Test
    fun `commands wait for asynchronous startup and keep their order`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val runtime = FakeRuntime()
        val surface = RecordingSurface()
        val panel = DesktopBrowserPanel(
            surface,
            backgroundScope,
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
        ) { _, _ ->
            ready.await()
            runtime
        }
        SwingUtilities.invokeAndWait { panel.createComponent() }
        runCurrent()
        assertTrue(surface.states.first().isLoading)
        assertEquals(BrowserRuntimeUi.Preparing, panel.preparation.value)
        SwingUtilities.invokeAndWait {
            panel.execute(BrowserViewCommand.Load("https://example.com/next"))
            panel.execute(BrowserViewCommand.Stop)
            panel.execute(BrowserViewCommand.Load("https://example.com/latest"))
        }
        ready.complete(Unit)
        runCurrent()

        assertEquals("", runtime.initialUrl)
        assertEquals(
            listOf(BrowserViewCommand.Stop, BrowserViewCommand.Load("https://example.com/latest")),
            runtime.commands,
        )
        SwingUtilities.invokeAndWait { panel.release() }
        assertTrue(runtime.isReleased)
        assertSame(panel, surface.attached.single())
        assertSame(panel, surface.detached.single())
    }

    @Test
    fun `reload retries a failed initialization with the last requested URL`() = runTest {
        var attempts = 0
        val runtime = FakeRuntime()
        val surface = RecordingSurface()
        val panel = DesktopBrowserPanel(
            surface,
            backgroundScope,
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
        ) { _, _ ->
            attempts++
            if (attempts == 1) error("failure")
            runtime
        }
        SwingUtilities.invokeAndWait { panel.createComponent() }
        runCurrent()
        assertEquals(BrowserViewError.EngineUnavailable, surface.states.last().error)
        SwingUtilities.invokeAndWait { panel.execute(BrowserViewCommand.Reload) }
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(surface.initialUrl, runtime.initialUrl)
        SwingUtilities.invokeAndWait { panel.release() }
    }

    @Test
    fun `disposal invalidates native callbacks and cancels pending startup`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val runtime = FakeRuntime()
        val surface = RecordingSurface()
        var callback: (BrowserViewState) -> Unit = {}
        val panel = DesktopBrowserPanel(
            surface,
            backgroundScope,
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
        ) { _, changed ->
            callback = changed
            ready.await()
            runtime
        }
        SwingUtilities.invokeAndWait { panel.createComponent() }
        runCurrent()
        SwingUtilities.invokeAndWait { panel.release() }
        ready.complete(Unit)
        runCurrent()
        callback(BrowserViewState(url = "https://example.com/stale"))
        SwingUtilities.invokeAndWait { Unit }

        assertFalse(runtime.isStarted)
        assertEquals(1, surface.states.size)
        assertEquals(1, surface.detached.size)
    }

    @Test
    fun `disposed composition never attaches a late Swing component`() = runTest {
        val surface = RecordingSurface()
        val panel = DesktopBrowserPanel(
            surface,
            backgroundScope,
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
        ) { _, _ ->
            error("must not initialize")
        }
        panel.release()
        SwingUtilities.invokeAndWait { panel.createComponent() }
        runCurrent()
        assertTrue(surface.attached.isEmpty())
        assertTrue(surface.states.isEmpty())
    }

    @Test
    fun `profile teardown awaits native close after removing the Swing surface`() = runTest {
        val nativeClosed = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(nativeClosed)
        val panel = DesktopBrowserPanel(
            RecordingSurface(),
            backgroundScope,
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
        ) { _, _ -> runtime }
        SwingUtilities.invokeAndWait { panel.createComponent() }
        runCurrent()
        SwingUtilities.invokeAndWait { panel.release() }
        val teardown = async { panel.awaitClosed() }
        runCurrent()
        assertTrue(runtime.isReleased)
        assertFalse(teardown.isCompleted)
        nativeClosed.complete(Unit)
        teardown.await()
    }

    @Test
    fun `automation and resource timeouts fail the call instead of cancelling it`() = runTest {
        val panel = DesktopBrowserPanel(
            RecordingSurface(),
            backgroundScope,
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
        ) { _, _ -> FakeRuntime() }
        SwingUtilities.invokeAndWait { panel.createComponent() }
        runCurrent()
        val automation = assertFailsWith<IllegalStateException> {
            panel.devTools(
                "Page.getFrameTree",
                JsonObject(emptyMap()),
            )
        }
        assertTrue(automation !is CancellationException)
        assertEquals("Browser automation call timed out", automation.message)
        val resource = assertFailsWith<IllegalStateException> { panel.captureResource("https://example.com/paper") }
        assertTrue(resource !is CancellationException)
        assertEquals("Browser resource capture timed out", resource.message)
        SwingUtilities.invokeAndWait { panel.release() }
    }

    @Test
    fun `download progress and failure stay visible and automation can retry without waiting for a timeout`() =
        runTest {
            val progress = MutableStateFlow<BrowserRuntimePreparation>(BrowserRuntimePreparation.Idle)
            val downloaded = CompletableDeferred<Unit>()
            val surface = RecordingSurface()
            var attempts = 0
            val panel = DesktopBrowserPanel(
                surface,
                backgroundScope,
                BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
                progress,
            ) { _, _ ->
                attempts++
                if (attempts == 1) {
                    downloaded.await()
                    progress.value = BrowserRuntimePreparation.Failed(BrowserRuntimeFailure.Network)
                    error("offline")
                }
                FakeRuntime(devToolsResponse = "ready")
            }
            SwingUtilities.invokeAndWait { panel.prepare() }
            runCurrent()
            progress.value = BrowserRuntimePreparation.Downloading(4L * 1024 * 1024, 8L * 1024 * 1024)
            runCurrent()
            assertEquals(BrowserRuntimeUi.Downloading(4, 50), panel.preparation.value)
            val pending = assertFailsWith<IllegalStateException> {
                panel.devTools(
                    "Page.getFrameTree",
                    JsonObject(emptyMap()),
                )
            }
            assertEquals("Browser components are being prepared; retry after setup finishes", pending.message)
            downloaded.complete(Unit)
            runCurrent()
            assertEquals(BrowserRuntimeUi.Failed(BrowserRuntimeProblem.Network), panel.preparation.value)
            SwingUtilities.invokeAndWait { panel.execute(BrowserViewCommand.Reload) }
            runCurrent()
            assertEquals(2, attempts)
            assertEquals(BrowserRuntimeUi.Ready, panel.preparation.value)
            assertEquals("ready", panel.devTools("Page.getFrameTree", JsonObject(emptyMap())))
            SwingUtilities.invokeAndWait { panel.release() }
        }

    @Test
    fun `automation still waits for ordinary native startup before the UI mounts`() = runTest {
        val started = CompletableDeferred<Unit>()
        val panel = DesktopBrowserPanel(
            RecordingSurface(),
            backgroundScope,
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
        ) { _, _ ->
            started.await()
            FakeRuntime(devToolsResponse = "ready")
        }
        val call = async { panel.devTools("Page.getFrameTree", JsonObject(emptyMap())) }
        runCurrent()
        assertFalse(call.isCompleted)
        SwingUtilities.invokeAndWait { panel.prepare() }
        runCurrent()
        assertFalse(call.isCompleted)
        started.complete(Unit)
        runCurrent()
        assertEquals("ready", call.await())
        SwingUtilities.invokeAndWait { panel.release() }
    }

    @Test
    fun `callbacks from a failed native attempt cannot replace the retried page`() = runTest {
        val callbacks = mutableListOf<(BrowserViewState) -> Unit>()
        val surface = RecordingSurface()
        val panel = DesktopBrowserPanel(
            surface,
            backgroundScope,
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
        ) { _, changed ->
            callbacks += changed
            FakeRuntime()
        }
        SwingUtilities.invokeAndWait { panel.prepare() }
        runCurrent()
        SwingUtilities.invokeAndWait { callbacks.first()(BrowserViewState(error = BrowserViewError.EngineUnavailable)) }
        SwingUtilities.invokeAndWait { panel.execute(BrowserViewCommand.Reload) }
        runCurrent()
        SwingUtilities.invokeAndWait {
            callbacks.last()(BrowserViewState(url = "https://example.com/new"))
            callbacks.first()(BrowserViewState(error = BrowserViewError.EngineUnavailable))
            callbacks.first()(BrowserViewState(url = "https://example.com/stale"))
        }
        assertEquals(BrowserRuntimeUi.Ready, panel.preparation.value)
        assertEquals("https://example.com/new", surface.states.last().url)
        SwingUtilities.invokeAndWait { panel.release() }
    }

    private class FakeRuntime(
        private val nativeClosed: CompletableDeferred<Unit>? = null,
        private val devToolsResponse: String? = null,
    ) : DesktopBrowserRuntime {
        override val component = JPanel()
        var initialUrl = ""
        var isStarted = false
        var isReleased = false
        val commands = mutableListOf<BrowserViewCommand>()
        override fun start(initialUrl: String) {
            isStarted = true
            this.initialUrl = initialUrl
        }
        override fun execute(command: BrowserViewCommand) {
            commands += command
        }
        override fun release() {
            isReleased = true
        }
        override suspend fun awaitClosed() {
            nativeClosed?.await()
        }
        override suspend fun devTools(method: String, arguments: JsonObject): String =
            devToolsResponse ?: awaitCancellation()
        override suspend fun captureResource(url: String): JsonObject = awaitCancellation()
    }

    private class RecordingSurface : BrowserSurface {
        override val nativeResources = BrowserNativeResources.None
        override val initialUrl = "https://example.com"
        val attached = mutableListOf<BrowserViewController>()
        val detached = mutableListOf<BrowserViewController>()
        val states = mutableListOf<BrowserViewState>()
        override fun attached(controller: BrowserViewController) {
            attached += controller
        }
        override fun changed(controller: BrowserViewController, state: BrowserViewState) {
            states += state
        }
        override fun detached(controller: BrowserViewController) {
            detached += controller
        }
    }
}
