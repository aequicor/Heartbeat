package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserZoomTest {
    private val page = BrowserPage(url = "https://example.com/")
    private val zoomable = BrowserState.Running(
        isConfigured = true,
        isEnabled = true,
        page = page,
        selectedTabId = "tab",
        isZoomEnabled = true,
    )

    @Test
    fun `zoom steps through Chrome's presets and stops at their bounds`() {
        assertEquals(110, browserZoomStep(100, BrowserZoom.In))
        assertEquals(90, browserZoomStep(100, BrowserZoom.Out))
        assertEquals(500, browserZoomStep(400, BrowserZoom.In))
        assertEquals(500, browserZoomStep(500, BrowserZoom.In))
        assertEquals(25, browserZoomStep(25, BrowserZoom.Out))
        assertEquals(33, browserZoomStep(50, BrowserZoom.Out))
        assertEquals(100, browserZoomStep(250, BrowserZoom.Reset))
        assertEquals(BrowserZoomLevels.sorted(), BrowserZoomLevels)
    }

    @Test
    fun `a level between presets moves to the nearest preset in the command's direction`() {
        assertEquals(125, browserZoomStep(115, BrowserZoom.In))
        assertEquals(110, browserZoomStep(115, BrowserZoom.Out))
        assertEquals(25, browserZoomStep(10, BrowserZoom.In))
        assertEquals(600, browserZoomStep(600, BrowserZoom.In))
        assertEquals(500, browserZoomStep(600, BrowserZoom.Out))
    }

    @Test
    fun `page zoom ships enabled and a page starts at the default level`() {
        assertTrue(BrowserPageZoom.default)
        assertEquals(BROWSER_DEFAULT_ZOOM, BrowserPage().zoomPercent)
    }

    @Test
    fun `zoom availability is projected into the running state`() {
        val running = zoomable.copy(isZoomEnabled = false)
        BrowserMachineSpec.assertTransition(
            running,
            BrowserIntent.Internal.ZoomAvailabilityChanged(isEnabled = true),
            running.copy(isZoomEnabled = true),
        )
        BrowserMachineSpec.assertTransition(
            zoomable,
            BrowserIntent.Internal.ZoomAvailabilityChanged(isEnabled = false),
            zoomable.copy(isZoomEnabled = false),
        )
    }

    @Test
    fun `zoom queues a direction without guessing the level of earlier pending commands`() {
        BrowserZoom.entries.forEach { direction ->
            BrowserMachineSpec.assertTransition(
                zoomable,
                BrowserIntent.Public.Zoom(direction),
                zoomable,
                listOf(BrowserEffect.Zoom("tab", direction, page.url)),
            )
        }
        val atLimit = zoomable.copy(page = page.copy(zoomPercent = 500))
        BrowserMachineSpec.assertTransition(
            atLimit,
            BrowserIntent.Public.Zoom(BrowserZoom.In),
            atLimit,
            listOf(BrowserEffect.Zoom("tab", BrowserZoom.In, page.url)),
        )
    }

    @Test
    fun `zoom on a page without a web host is absorbed without an effect`() {
        val blank = zoomable.copy(page = BrowserPage())
        BrowserMachineSpec.assertTransition(blank, BrowserIntent.Public.Zoom(BrowserZoom.In), blank)
    }

    @Test
    fun `zoom without an enabled browser or toggle is ignored`() {
        BrowserMachineSpec.assertIgnored(
            zoomable.copy(isZoomEnabled = false),
            BrowserIntent.Public.Zoom(BrowserZoom.In),
        )
        BrowserMachineSpec.assertIgnored(zoomable.copy(isEnabled = false), BrowserIntent.Public.Zoom(BrowserZoom.In))
        BrowserMachineSpec.assertIgnored(BrowserState.Idle, BrowserIntent.Public.Zoom(BrowserZoom.In))
    }

    @Test
    fun `the confirmed page level updates the machine`() {
        val previous = zoomable.copy(page = page.copy(zoomPercent = 110))
        BrowserMachineSpec.assertTransition(
            previous,
            BrowserIntent.Internal.PageChanged(page.copy(zoomPercent = 125)),
            previous.copy(page = page.copy(zoomPercent = 125)),
        )
    }
}
