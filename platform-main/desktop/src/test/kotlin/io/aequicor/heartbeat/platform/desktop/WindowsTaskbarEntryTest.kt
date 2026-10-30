package io.aequicor.heartbeat.platform.desktop

import java.awt.EventQueue
import java.awt.event.ComponentEvent
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsTaskbarEntryTest {
    @Test
    fun trayHideRestoreAndDisposalBalanceTaskbarEntriesWithoutDuplicates() {
        val window = JFrame()
        val updates = mutableListOf<Boolean>()
        var isVisible = false
        val entry = WindowsTaskbarEntry(window, isShowing = { isVisible }, update = updates::add)
        fun visibility(value: Boolean) {
            EventQueue.invokeAndWait {
                isVisible = value
                window.dispatchEvent(ComponentEvent(window, ComponentEvent.COMPONENT_SHOWN))
                window.dispatchEvent(ComponentEvent(window, ComponentEvent.COMPONENT_HIDDEN))
            }
            EventQueue.invokeAndWait { }
        }
        try {
            visibility(true)
            visibility(true)
            visibility(false)
            visibility(true)
            EventQueue.invokeAndWait { entry.close() }
            visibility(true)
            assertEquals(listOf(true, false, true, false), updates)
        } finally {
            EventQueue.invokeAndWait {
                entry.close()
                window.dispose()
            }
        }
    }
}
