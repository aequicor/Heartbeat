package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun `page zoom requires explicit opt in`() {
        assertFalse(BrowserPageZoom.default)
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
    fun `zoom applies the next level to the selected tab's site at once`() {
        BrowserMachineSpec.assertTransition(
            zoomable,
            BrowserIntent.Public.Zoom(BrowserZoom.In),
            zoomable.copy(page = page.copy(zoomPercent = 110)),
            listOf(BrowserEffect.Zoom("tab", 110)),
        )
        val zoomed = zoomable.copy(page = page.copy(zoomPercent = 150))
        BrowserMachineSpec.assertTransition(
            zoomed,
            BrowserIntent.Public.Zoom(BrowserZoom.Out),
            zoomed.copy(page = page.copy(zoomPercent = 125)),
            listOf(BrowserEffect.Zoom("tab", 125)),
        )
        BrowserMachineSpec.assertTransition(
            zoomed,
            BrowserIntent.Public.Zoom(BrowserZoom.Reset),
            zoomable,
            listOf(BrowserEffect.Zoom("tab", BROWSER_DEFAULT_ZOOM)),
        )
    }

    @Test
    fun `zoom that changes nothing or has nothing to zoom is ignored`() {
        BrowserMachineSpec.assertIgnored(zoomable, BrowserIntent.Public.Zoom(BrowserZoom.Reset))
        BrowserMachineSpec.assertIgnored(
            zoomable.copy(page = page.copy(zoomPercent = 500)),
            BrowserIntent.Public.Zoom(BrowserZoom.In),
        )
        BrowserMachineSpec.assertIgnored(
            zoomable.copy(isZoomEnabled = false),
            BrowserIntent.Public.Zoom(BrowserZoom.In),
        )
        BrowserMachineSpec.assertIgnored(zoomable.copy(isEnabled = false), BrowserIntent.Public.Zoom(BrowserZoom.In))
        BrowserMachineSpec.assertIgnored(zoomable.copy(page = BrowserPage()), BrowserIntent.Public.Zoom(BrowserZoom.In))
        BrowserMachineSpec.assertIgnored(BrowserState.Idle, BrowserIntent.Public.Zoom(BrowserZoom.In))
    }

    @Test
    fun `the page's own level replaces the optimistic one`() {
        val optimistic = zoomable.copy(page = page.copy(zoomPercent = 110))
        BrowserMachineSpec.assertTransition(
            optimistic,
            BrowserIntent.Internal.PageChanged(page.copy(zoomPercent = 125)),
            optimistic.copy(page = page.copy(zoomPercent = 125)),
        )
    }
}
