package io.aequicor.heartbeat.ds.components

import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HbDialogWindowPlacementTest {
    @Test
    fun `the owned dialog follows client coordinates after root movement and content resize`() = onEdt {
        val owner = JPanel()
        val client = Client()
        val bounds = mutableListOf<Rectangle>()
        val placement = HbDialogWindowPlacement(owner, client, bounds::add)
        placement.install()
        assertEquals(Rectangle(100, 120, 800, 600), bounds.last())
        client.screen = Point(140, 170)
        owner.dispatchEvent(ComponentEvent(owner, ComponentEvent.COMPONENT_MOVED))
        assertEquals(Rectangle(140, 170, 800, 600), bounds.last())
        client.setSize(500, 400)
        client.dispatchEvent(ComponentEvent(client, ComponentEvent.COMPONENT_RESIZED))
        assertEquals(Rectangle(140, 170, 500, 400), bounds.last())
        placement.close()
    }

    @Test
    fun `hidden clients wait for showing and queued events cannot move a disposed dialog`() = onEdt {
        val owner = JPanel()
        val client = Client().apply { isClientShowing = false }
        val bounds = mutableListOf<Rectangle>()
        val placement = HbDialogWindowPlacement(owner, client, bounds::add)
        placement.install()
        assertTrue(bounds.isEmpty())
        client.isClientShowing = true
        client.dispatchEvent(ComponentEvent(client, ComponentEvent.COMPONENT_SHOWN))
        assertEquals(1, bounds.size)
        val queued = owner.componentListeners.single()
        placement.close()
        placement.close()
        client.screen = Point(200, 300)
        queued.componentMoved(ComponentEvent(owner, ComponentEvent.COMPONENT_MOVED))
        placement.install()
        assertEquals(1, bounds.size)
        assertTrue(owner.componentListeners.isEmpty())
        assertTrue(client.componentListeners.isEmpty())
    }

    @Test
    fun `an owner that is its own client installs only one listener`() = onEdt {
        val client = Client()
        val placement = HbDialogWindowPlacement(client, client) { }
        placement.install()
        placement.install()
        assertEquals(1, client.componentListeners.size)
        placement.close()
        assertTrue(client.componentListeners.isEmpty())
    }

    private class Client : JPanel() {
        var screen = Point(100, 120)
        var isClientShowing = true

        init {
            setSize(800, 600)
        }

        override fun isShowing(): Boolean = isClientShowing
        override fun getLocationOnScreen(): Point = Point(screen)
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
