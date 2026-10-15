package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDocumentDismissalTest {
    @Test
    fun `reusing the same callback still revokes an Escape from the previous attachment generation`() {
        val queued = mutableListOf<() -> Unit>()
        val dismissal = DesktopDocumentDismissal(queued::add)
        var calls = 0
        val action: () -> Unit = { calls++ }
        dismissal.update(action)
        dismissal.request()
        dismissal.update(action)
        queued.removeAt(0).invoke()
        assertEquals(0, calls)
        dismissal.request()
        queued.removeAt(0).invoke()
        assertEquals(1, calls)
    }

    @Test
    fun `Escape outside a modal is ignored and the active modal receives it on dispatch`() {
        val queued = mutableListOf<() -> Unit>()
        val dismissal = DesktopDocumentDismissal(queued::add)
        var calls = 0
        assertFalse(dismissal.request())
        dismissal.update { calls++ }
        assertTrue(dismissal.request())
        assertEquals(0, calls)
        queued.removeAt(0).invoke()
        assertEquals(1, calls)
    }

    @Test
    fun `an Escape queued before reparent or close cannot dismiss a replacement modal`() {
        val queued = mutableListOf<() -> Unit>()
        val dismissal = DesktopDocumentDismissal(queued::add)
        var oldCalls = 0
        var newCalls = 0
        dismissal.update { oldCalls++ }
        dismissal.request()
        dismissal.update { newCalls++ }
        queued.removeAt(0).invoke()
        assertEquals(0, oldCalls)
        assertEquals(0, newCalls)
        dismissal.request()
        dismissal.update(null)
        queued.removeAt(0).invoke()
        assertEquals(0, newCalls)
        assertFalse(dismissal.request())
    }
}
