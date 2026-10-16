package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserShortcutTest {
    @Test
    fun `accelerator keys follow Chromium`() {
        assertEquals(BrowserShortcut.NewTab, accelerated('t'))
        assertEquals(BrowserShortcut.ReopenClosedTab, accelerated('t', isShift = true))
        assertEquals(BrowserShortcut.CloseTab, accelerated('w'))
        assertEquals(BrowserShortcut.SelectTab(1), accelerated('1'))
        assertEquals(BrowserShortcut.SelectTab(8), accelerated('8'))
        assertEquals(BrowserShortcut.SelectTab(null), accelerated('9'))
    }

    @Test
    fun `control tab switches tabs on every host`() {
        assertEquals(BrowserShortcut.NextTab, browserShortcut('\t', false, isControl = true, isShift = false, false))
        assertEquals(BrowserShortcut.PreviousTab, browserShortcut('\t', false, isControl = true, isShift = true, false))
        assertNull(browserShortcut('\t', isAccelerator = true, isControl = false, isShift = false, isAlt = false))
    }

    @Test
    fun `other combinations stay with the page`() {
        assertNull(browserShortcut('t', isAccelerator = false, isControl = false, isShift = false, isAlt = false))
        assertNull(accelerated('w', isShift = true))
        assertNull(accelerated('1', isShift = true))
        assertNull(browserShortcut('t', isAccelerator = true, isControl = true, isShift = false, isAlt = true))
        assertNull(accelerated('x'))
    }

    private fun accelerated(key: Char, isShift: Boolean = false) =
        browserShortcut(key, isAccelerator = true, isControl = false, isShift = isShift, isAlt = false)
}
