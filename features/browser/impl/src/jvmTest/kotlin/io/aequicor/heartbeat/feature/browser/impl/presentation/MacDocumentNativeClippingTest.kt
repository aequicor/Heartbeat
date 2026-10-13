package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.event.HierarchyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacDocumentNativeClippingTest {
    @Test
    fun `a finite attachment burst continues after finding existing siblings`() = onEdt {
        val fixture = Fixture()
        fixture.adapter.request()
        fixture.retries.drain()
        assertEquals(4, fixture.access.calls)
        assertEquals(listOf(100, 150, 250), fixture.retries.delays)
        assertEquals(0, fixture.failures)
        fixture.adapter.close()
    }

    @Test
    fun `a superseded request ignores callbacks already queued by the old burst`() = onEdt {
        val fixture = Fixture()
        fixture.adapter.request()
        val previous = fixture.retries.take()
        fixture.adapter.request()
        previous.action()
        assertTrue(previous.isCancelled)
        assertEquals(2, fixture.access.calls)
        fixture.retries.drain()
        assertEquals(5, fixture.access.calls)
        fixture.adapter.close()
    }

    @Test
    fun `closure cancels retries removes its listener and rejects late callbacks`() = onEdt {
        val fixture = Fixture()
        fixture.adapter.request()
        val queued = fixture.retries.take()
        fixture.adapter.close()
        fixture.adapter.close()
        queued.action()
        fixture.adapter.request()
        assertTrue(queued.isCancelled)
        assertEquals(1, fixture.access.calls)
        assertTrue(fixture.component.hierarchyListeners.isEmpty())
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `hidden documents defer work until shown without exhausting the attachment budget`() = onEdt {
        val fixture = Fixture()
        fixture.component.isShown = false
        fixture.adapter.documentLoaded()
        assertEquals(0, fixture.access.calls)
        assertTrue(fixture.retries.queue.isEmpty())
        fixture.component.isShown = true
        fixture.component.showingChanged()
        assertEquals(1, fixture.access.calls)
        val queued = fixture.retries.take()
        fixture.component.isShown = false
        fixture.component.showingChanged()
        queued.action()
        assertEquals(1, fixture.access.calls)
        assertEquals(0, fixture.failures)
        fixture.component.isShown = true
        fixture.component.showingChanged()
        fixture.retries.drain()
        assertEquals(5, fixture.access.calls)
        fixture.adapter.close()
    }

    @Test
    fun `a slow initial load can attach after the early burst without becoming unavailable`() = onEdt {
        val fixture = Fixture()
        fixture.access.count = 0
        fixture.adapter.request()
        fixture.retries.drain()
        assertEquals(4, fixture.access.calls)
        assertEquals(0, fixture.failures)
        assertTrue(fixture.component.hierarchyListeners.isNotEmpty())
        fixture.access.count = 1
        fixture.adapter.documentLoaded()
        fixture.retries.drain()
        assertEquals(8, fixture.access.calls)
        assertEquals(0, fixture.failures)
        fixture.adapter.close()
    }

    @Test
    fun `no window attachment after the final attempt fails once and closes the adapter`() = onEdt {
        val fixture = Fixture()
        fixture.access.count = 0
        fixture.adapter.documentLoaded()
        fixture.retries.drain()
        fixture.adapter.request()
        assertEquals(4, fixture.access.calls)
        assertEquals(1, fixture.failures)
        assertTrue(fixture.component.hierarchyListeners.isEmpty())
    }

    @Test
    fun `native failure reports unavailable without scheduling retries`() = onEdt {
        val fixture = Fixture()
        fixture.access.error = IllegalStateException("Native failure")
        fixture.adapter.request()
        fixture.adapter.request()
        assertEquals(1, fixture.access.calls)
        assertEquals(1, fixture.failures)
        assertTrue(fixture.retries.queue.isEmpty())
    }

    @Test
    fun `a document without a live peer never queries the native window`() = onEdt {
        val fixture = Fixture()
        fixture.component.isPeerReady = false
        fixture.adapter.request()
        assertEquals(0, fixture.access.calls)
        assertTrue(fixture.retries.queue.isEmpty())
        assertEquals(0, fixture.failures)
        fixture.adapter.close()
    }

    private class Fixture {
        val component = DocumentComponent()
        val access = Access()
        val retries = Retries()
        var failures = 0
        val adapter = MacDocumentNativeClipping(component, { failures++ }, access, retries)
    }

    private class DocumentComponent : JPanel() {
        var isShown = true
        var isPeerReady = true
        override fun isShowing(): Boolean = isShown
        override fun isDisplayable(): Boolean = isPeerReady

        fun showingChanged() {
            val event = HierarchyEvent(
                this,
                HierarchyEvent.HIERARCHY_CHANGED,
                this,
                null,
                HierarchyEvent.SHOWING_CHANGED.toLong(),
            )
            hierarchyListeners.forEach { it.hierarchyChanged(event) }
        }
    }

    private class Access : MacDocumentClipAccess {
        var calls = 0
        var count = 1
        var error: Exception? = null
        override fun apply(component: JComponent): Int {
            calls++
            error?.let { throw it }
            return count
        }
    }

    private class Retries : DocumentClipRetries {
        val queue = ArrayDeque<Pending>()
        val delays = mutableListOf<Int>()
        override fun after(milliseconds: Int, action: () -> Unit): AutoCloseable {
            delays += milliseconds
            val pending = Pending(action)
            queue.add(pending)
            return AutoCloseable { pending.isCancelled = true }
        }

        fun take(): Pending = queue.removeFirst()
        fun drain() {
            while (queue.isNotEmpty()) take().let { if (!it.isCancelled) it.action() }
        }
    }

    private class Pending(val action: () -> Unit) {
        var isCancelled = false
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
