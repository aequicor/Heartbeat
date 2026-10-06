package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private class FakeRuntime : DesktopBrowserRuntime {
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
