package io.aequicor.heartbeat.ds.components

import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HbNativeModalVisibilityTest {
    @Test
    fun `a modal hides only native peers in its own root and admits its exact owner`() = onEdt {
        val firstRoot = JRootPane()
        val secondRoot = JRootPane()
        val first = Peer(firstRoot)
        val second = Peer(secondRoot)
        val owner = HbNativeModalOwner()
        firstRoot.modal(owner)
        assertFalse(first.component.isVisible)
        assertTrue(second.component.isVisible)

        first.visibility.updateOwner(owner)
        assertTrue(first.component.isVisible)
        firstRoot.modal(HbNativeModalOwner())
        assertFalse(first.component.isVisible, "A superseded modal cannot remain above the current modal")
        firstRoot.modal(null)
        assertTrue(first.component.isVisible)
        first.visibility.detach()
        second.visibility.detach()
    }

    @Test
    fun `a peer attached after a modal opens is hidden until assigned to the active owner`() = onEdt {
        val root = JRootPane()
        val owner = HbNativeModalOwner()
        root.modal(owner)
        val peer = Peer(root)
        assertFalse(peer.component.isVisible)
        peer.visibility.attach()
        assertFalse(peer.component.isVisible, "Repeated attachment must not replace the saved visible state")
        peer.visibility.updateOwner(owner)
        assertTrue(peer.component.isVisible)
        peer.visibility.updateOwner(null)
        assertFalse(peer.component.isVisible)
        peer.visibility.detach()
        assertTrue(peer.component.isVisible)
    }

    @Test
    fun `reparenting restores the prior visibility and follows only the new root`() = onEdt {
        val firstRoot = JRootPane()
        val secondRoot = JRootPane()
        val peer = Peer(firstRoot)
        firstRoot.modal(HbNativeModalOwner())
        assertFalse(peer.component.isVisible)

        secondRoot.contentPane.add(peer.component)
        assertTrue(peer.component.isVisible)
        firstRoot.modal(null)
        firstRoot.modal(HbNativeModalOwner())
        assertTrue(peer.component.isVisible, "The former root no longer owns this peer")
        secondRoot.modal(HbNativeModalOwner())
        assertFalse(peer.component.isVisible)
        secondRoot.contentPane.remove(peer.component)
        assertTrue(peer.component.isVisible)
        peer.visibility.detach()
    }

    @Test
    fun `an originally hidden peer stays hidden after modal release and detach`() = onEdt {
        val root = JRootPane()
        val peer = Peer(root, isVisible = false)
        root.modal(HbNativeModalOwner())
        root.modal(null)
        assertFalse(peer.component.isVisible)
        root.modal(HbNativeModalOwner())
        peer.visibility.detach()
        assertFalse(peer.component.isVisible)
        root.modal(null)
        assertFalse(peer.component.isVisible)
    }

    @Test
    fun `detached peers ignore later owner changes and can start a new visibility lease`() = onEdt {
        val root = JRootPane()
        val peer = Peer(root)
        root.modal(HbNativeModalOwner())
        assertFalse(peer.component.isVisible)
        peer.visibility.detach()
        peer.visibility.detach()
        assertTrue(peer.component.isVisible)
        root.modal(HbNativeModalOwner())
        assertTrue(peer.component.isVisible)
        assertTrue(root.getPropertyChangeListeners(HbNativeModalOwner.PROPERTY).isEmpty())

        peer.visibility.attach()
        assertFalse(peer.component.isVisible)
        peer.visibility.detach()
        assertTrue(peer.component.isVisible)
    }

    private class Peer(root: JRootPane, isVisible: Boolean = true) {
        val component = JPanel().apply { this.isVisible = isVisible }
        val visibility = HbNativeModalVisibility(component)

        init {
            root.contentPane.add(component)
            visibility.attach()
        }
    }

    private fun JRootPane.modal(owner: HbNativeModalOwner?) = putClientProperty(HbNativeModalOwner.PROPERTY, owner)

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
