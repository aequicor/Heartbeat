package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserError
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserSurfaceAdapterTest {
    private fun TestScope.createAdapter(): BrowserSurfaceAdapter = BrowserSurfaceAdapter(
        BrowserTestScope(backgroundScope),
        BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
    )

    @Test
    fun `initial view is blank and load then stop are retained until attachment`() = runTest {
        val adapter = createAdapter()
        assertEquals("", adapter.initialUrl)
        adapter.setEnabled(true)
        adapter.execute(BrowserCommand.Load("https://example.com"))
        adapter.execute(BrowserCommand.Stop)
        assertFalse(adapter.pages.value.isLoading)
        assertEquals("https://example.com", adapter.initialUrl)
        val view = RecordingBrowserController()
        adapter.attached(view)
        runCurrent()
        assertEquals(listOf(BrowserViewCommand.Load("https://example.com"), BrowserViewCommand.Stop), view.commands)
        adapter.attached(view)
        runCurrent()
        assertEquals(2, view.commands.size)
    }

    @Test
    fun `detached and replaced views cannot overwrite the retained page`() = runTest {
        val adapter = createAdapter()
        adapter.setEnabled(true)
        val old = RecordingBrowserController()
        val current = RecordingBrowserController()
        adapter.attached(old)
        adapter.changed(old, BrowserViewState("https://example.com/first", isBackAvailable = true))
        runCurrent()
        adapter.detached(old)
        adapter.changed(old, BrowserViewState("https://example.com/stale"))
        runCurrent()
        assertEquals("https://example.com/first", adapter.initialUrl)
        assertFalse(adapter.pages.value.isBackAvailable)
        adapter.attached(current)
        adapter.changed(current, BrowserViewState("https://example.com/current"))
        adapter.detached(old)
        adapter.changed(old, BrowserViewState("https://example.com/stale-again"))
        runCurrent()
        adapter.execute(BrowserCommand.Reload)
        assertEquals("https://example.com/current", adapter.initialUrl)
        assertEquals(listOf<BrowserViewCommand>(BrowserViewCommand.Reload), current.commands)
    }

    @Test
    fun `disabling stops controller discards pending commands and rejects late feedback`() = runTest {
        val adapter = createAdapter()
        adapter.setEnabled(true)
        val old = RecordingBrowserController()
        adapter.attached(old)
        runCurrent()
        adapter.execute(BrowserCommand.Load("https://example.com"))
        adapter.setEnabled(false)
        adapter.changed(old, BrowserViewState("https://example.com/late", isLoading = true))
        adapter.execute(BrowserCommand.Load("https://example.com/blocked"))
        runCurrent()
        assertEquals(BrowserViewCommand.Stop, old.commands.last())
        assertEquals("https://example.com", adapter.initialUrl)
        assertFalse(adapter.pages.value.isLoading)
        adapter.setEnabled(true)
        val current = RecordingBrowserController()
        adapter.attached(current)
        runCurrent()
        assertTrue(current.commands.isEmpty())
    }

    @Test
    fun `disabling before attach drops commands and stops a late attachment`() = runTest {
        val adapter = createAdapter()
        adapter.setEnabled(true)
        adapter.execute(BrowserCommand.Load("https://example.com"))
        adapter.setEnabled(false)
        val view = RecordingBrowserController()
        adapter.attached(view)
        runCurrent()
        assertEquals(listOf<BrowserViewCommand>(BrowserViewCommand.Stop), view.commands)
    }

    @Test
    fun `unsafe callbacks cannot replace the last safe URL and native errors are mapped`() = runTest {
        val adapter = createAdapter()
        adapter.setEnabled(true)
        val view = RecordingBrowserController()
        adapter.attached(view)
        adapter.changed(view, BrowserViewState("https://example.com", title = "Page"))
        adapter.changed(view, BrowserViewState("file:///secret"))
        runCurrent()
        assertEquals("https://example.com", adapter.initialUrl)
        assertEquals(BrowserError.UnsupportedAddress, adapter.pages.value.error)
        adapter.changed(view, BrowserViewState(error = BrowserViewError.EngineUnavailable))
        runCurrent()
        assertEquals("https://example.com", adapter.initialUrl)
        assertEquals(BrowserError.EngineUnavailable, adapter.pages.value.error)
    }

    @Test
    fun `closing the retained scope stops native work and rejects future attachments`() = runTest {
        val scope = BrowserTestScope(backgroundScope)
        val adapter = BrowserSurfaceAdapter(scope, BrowserTestDispatchers(StandardTestDispatcher(testScheduler)))
        adapter.setEnabled(true)
        val view = RecordingBrowserController()
        adapter.attached(view)
        runCurrent()
        scope.close()
        adapter.changed(view, BrowserViewState("https://example.com/late"))
        adapter.setEnabled(true)
        adapter.execute(BrowserCommand.Reload)
        runCurrent()
        assertEquals(listOf<BrowserViewCommand>(BrowserViewCommand.Stop), view.commands)
        assertEquals("", adapter.initialUrl)
    }
}
