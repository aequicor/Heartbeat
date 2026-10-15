package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDocumentFocusExitTest {
    @Test
    fun `a native boundary dispatches to the current overlay on EDT`() {
        val queued = mutableListOf<() -> Unit>()
        val exit = DesktopDocumentFocusExit(queued::add)
        var focused = 0
        assertFalse(exit.request())
        exit.update { focused++ }
        assertTrue(exit.request())
        assertEquals(0, focused)
        queued.removeAt(0).invoke()
        assertEquals(1, focused)
    }

    @Test
    fun `a boundary queued before reparent cannot focus an old or replacement overlay`() {
        val queued = mutableListOf<() -> Unit>()
        val exit = DesktopDocumentFocusExit(queued::add)
        var oldCalls = 0
        var newCalls = 0
        exit.update { oldCalls++ }
        exit.request()
        exit.update { newCalls++ }
        queued.removeAt(0).invoke()
        assertEquals(0, oldCalls)
        assertEquals(0, newCalls)
        exit.request()
        queued.removeAt(0).invoke()
        assertEquals(1, newCalls)
        exit.request()
        exit.update(null)
        queued.removeAt(0).invoke()
        assertEquals(1, newCalls)
        assertFalse(exit.request())
    }

    @Test
    fun `reusing an overlay callback still revokes the old attachment generation`() {
        val queued = mutableListOf<() -> Unit>()
        val exit = DesktopDocumentFocusExit(queued::add)
        var focused = 0
        val focus = { focused += 1 }
        exit.update(focus)
        exit.request()
        exit.update(focus)
        queued.removeAt(0).invoke()
        assertEquals(0, focused)
    }
}
