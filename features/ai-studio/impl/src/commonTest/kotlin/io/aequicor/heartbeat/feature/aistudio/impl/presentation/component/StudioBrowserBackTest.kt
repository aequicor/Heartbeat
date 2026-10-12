package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.backhandler.BackDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioBrowserBackTest {
    @Test
    fun `back hides a shown browser before the studio navigation and passes through once hidden`() {
        val dispatcher = BackDispatcher()
        var workspaceBacks = 0
        var hides = 0
        // The workspace stack registers first, as in the studio component.
        dispatcher.register(BackCallback { workspaceBacks++ })
        val browser = StudioBrowserBack { hides++ }
        dispatcher.register(browser.callback)

        for (mode in listOf(BrowserPaneMode.Expanded, BrowserPaneMode.Panel)) {
            browser.update(mode)
            assertTrue(dispatcher.back())
        }
        assertEquals(2, hides)
        assertEquals(0, workspaceBacks)

        browser.update(BrowserPaneMode.Hidden)
        assertTrue(dispatcher.back())
        assertEquals(2, hides)
        assertEquals(1, workspaceBacks)
    }

    @Test
    fun `hidden browser leaves back to the parent`() {
        val dispatcher = BackDispatcher()
        dispatcher.register(StudioBrowserBack { error("A hidden browser must not intercept back") }.callback)

        assertFalse(dispatcher.back())
    }
}
