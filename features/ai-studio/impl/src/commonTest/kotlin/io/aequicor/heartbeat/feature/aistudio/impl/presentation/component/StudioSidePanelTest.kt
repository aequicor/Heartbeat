package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import kotlin.test.Test
import kotlin.test.assertEquals

class StudioSidePanelTest {
    @Test
    fun `the panel opened last wins and the other one shows when it closes`() {
        val browser = SideChoice().next(BrowserPaneMode.Panel, panel = null)
        assertEquals(SidePanelUi.Browser, browser.shown)

        val opened = browser.next(BrowserPaneMode.Panel, panel = 1)
        assertEquals(SidePanelUi.Interface, opened.shown)

        val expanded = opened.next(BrowserPaneMode.Expanded, panel = 1)
        assertEquals(SidePanelUi.Interface, expanded.shown, "a browser already open does not take the side back")

        val closed = expanded.next(BrowserPaneMode.Expanded, panel = null)
        assertEquals(SidePanelUi.Browser, closed.shown)

        val reopened = closed.next(BrowserPaneMode.Expanded, panel = 2)
        assertEquals(SidePanelUi.Interface, reopened.shown)
    }

    @Test
    fun `a browser opened after the interface panel wins until it hides`() {
        val panel = SideChoice().next(BrowserPaneMode.Hidden, panel = 1)
        assertEquals(SidePanelUi.Interface, panel.shown)

        val browser = panel.next(BrowserPaneMode.Panel, panel = 1)
        assertEquals(SidePanelUi.Browser, browser.shown)

        assertEquals(SidePanelUi.Interface, browser.next(BrowserPaneMode.Hidden, panel = 1).shown)
        assertEquals(SidePanelUi.None, browser.next(BrowserPaneMode.Hidden, panel = null).shown)
    }
}
