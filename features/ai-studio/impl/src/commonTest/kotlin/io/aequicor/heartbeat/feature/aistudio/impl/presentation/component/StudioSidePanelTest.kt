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

    @Test
    fun `raising an open browser behind the interface panel brings it to the front`() {
        val covered = SideChoice().next(BrowserPaneMode.Panel, panel = null).next(BrowserPaneMode.Panel, panel = 1)
        assertEquals(SidePanelUi.Interface, covered.shown)

        val raised = covered.next(BrowserPaneMode.Panel, panel = 1, raise = 1)
        assertEquals(SidePanelUi.Browser, raised.shown)
        assertEquals(SidePanelUi.Browser, raised.next(BrowserPaneMode.Panel, panel = 1).shown)
        assertEquals(SidePanelUi.Interface, raised.next(BrowserPaneMode.Panel, panel = 2).shown)
    }

    @Test
    fun `a newly opened file covers the other panels and they return when it closes`() {
        val covered = SideChoice().next(BrowserPaneMode.Panel, panel = 1)
        val file = covered.next(BrowserPaneMode.Panel, panel = 1, file = 1)
        assertEquals(SidePanelUi.File, file.shown)
        assertEquals(SidePanelUi.File, file.next(BrowserPaneMode.Panel, panel = 1).shown, "nothing new opened")

        val raised = file.next(BrowserPaneMode.Panel, panel = 1, raise = 1)
        assertEquals(SidePanelUi.Browser, raised.shown)
        assertEquals(SidePanelUi.File, raised.next(BrowserPaneMode.Panel, panel = 1, file = 2).shown)

        assertEquals(SidePanelUi.Interface, file.next(BrowserPaneMode.Panel, panel = 1, file = null).shown)
        val onlyFile = SideChoice().next(BrowserPaneMode.Hidden, null, file = 1)
        assertEquals(SidePanelUi.None, onlyFile.next(BrowserPaneMode.Hidden, null, file = null).shown)
    }

    @Test
    fun `an open file shows when the panel in front of it closes`() {
        val file = SideChoice().next(BrowserPaneMode.Hidden, panel = null, file = 1)
        val panel = file.next(BrowserPaneMode.Hidden, panel = 1)
        assertEquals(SidePanelUi.Interface, panel.shown)
        assertEquals(SidePanelUi.File, panel.next(BrowserPaneMode.Hidden, panel = null).shown)
    }
}
