package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionGuideVisibilityTest {
    @Test
    fun `a show queued before suppression cannot reveal a panel missing from its native snapshot`() {
        val host = FakeHost()
        host.guide.place(true)
        val lease = host.guide.suppress()
        host.runQueued()
        assertTrue(host.visibilityWrites.isEmpty())
        assertFalse(host.isVisible)
        assertFalse(host.isNativeVisible)

        lease.close()
        host.runQueued()
        assertTrue(host.isVisible)
        assertTrue(host.isNativeVisible)
        host.guide.close()
    }

    @Test
    fun `the visibility gate closes before native suppression starts`() {
        val host = FakeHost()
        host.guide.place(true)
        host.onSuppress = host::runQueued
        val lease = host.guide.suppress()
        assertTrue(host.visibilityWrites.isEmpty())
        assertFalse(host.isNativeVisible)
        lease.close()
        host.runQueued()
        assertTrue(host.isNativeVisible)
        host.guide.close()
    }

    @Test
    fun `placement requested during capture waits until every nested lease ends`() {
        val host = FakeHost()
        val first = host.guide.suppress()
        val second = host.guide.suppress()
        host.guide.place(true)
        host.runQueued()
        assertFalse(host.isVisible)
        first.close()
        first.close()
        host.runQueued()
        assertFalse(host.isVisible)
        second.close()
        host.runQueued()
        assertTrue(host.isNativeVisible)
        host.guide.close()
    }

    @Test
    fun `visible panels keep AWT visibility without admitting another show while natively hidden`() {
        val host = FakeHost()
        host.guide.place(true)
        host.runQueued()
        host.visibilityWrites.clear()
        val lease = host.guide.suppress()
        host.guide.place(true)
        host.runQueued()
        assertTrue(host.isVisible)
        assertFalse(host.isNativeVisible)
        assertTrue(host.visibilityWrites.isEmpty())

        host.guide.place(false)
        host.runQueued()
        assertTrue(host.isVisible)
        assertFalse(host.isNativeVisible)
        lease.close()
        host.runQueued()
        assertFalse(host.isVisible)
        assertFalse(host.isNativeVisible)
        host.guide.close()
    }

    @Test
    fun `release before the deferred show still applies the latest placement`() {
        val host = FakeHost()
        host.guide.place(true)
        val lease = host.guide.suppress()
        lease.close()
        host.runQueued()
        assertTrue(host.isNativeVisible)
        host.guide.close()
    }

    @Test
    fun `a queued show made obsolete by hiding never becomes visible`() {
        val host = FakeHost()
        host.guide.place(true)
        val lease = host.guide.suppress()
        host.guide.place(false)
        lease.close()
        host.runQueued()
        assertFalse(host.visibilityWrites.any { it })
        assertFalse(host.isVisible)
        assertFalse(host.isNativeVisible)
        host.guide.close()
    }

    @Test
    fun `disposal rejects queued shows even after a suppression lease returns`() {
        val host = FakeHost()
        host.guide.place(true)
        host.runQueued()
        host.visibilityWrites.clear()
        host.guide.place(true)
        val lease = host.guide.suppress()
        host.guide.close()
        host.guide.close()
        lease.close()
        host.guide.place(true)
        host.runQueued()
        assertEquals(1, host.disposals)
        assertTrue(host.visibilityWrites.isEmpty())
        assertFalse(host.isNativeVisible)
    }

    @Test
    fun `native suppression failure reopens the gate for the pending show`() {
        val host = FakeHost()
        host.onSuppress = { error("native hide failed") }
        host.guide.place(true)
        assertFailsWith<IllegalStateException> { host.guide.suppress() }
        host.runQueued()
        assertTrue(host.isNativeVisible)
        host.guide.close()
    }

    /** Queued AWT updates and native snapshots are independent, reproducing a next-event-tick show. */
    private class FakeHost {
        private val queue = ArrayDeque<() -> Unit>()
        private var nativeLeases = 0
        private var wasNativeVisible = false
        var isVisible = false
            private set
        var isNativeVisible = false
            private set
        var disposals = 0
            private set
        var onSuppress: () -> Unit = {}
        val visibilityWrites = mutableListOf<Boolean>()
        val guide = PermissionGuidePresentation(
            native = ComputerUsePresentation {
                onSuppress()
                if (nativeLeases++ == 0) {
                    wasNativeVisible = isNativeVisible
                    isNativeVisible = false
                }
                AutoCloseable {
                    if (--nativeLeases == 0 && disposals == 0 && isVisible) {
                        isNativeVisible = wasNativeVisible
                    }
                }
            },
            dispose = {
                disposals++
                isVisible = false
                isNativeVisible = false
            },
            setVisible = { isShown ->
                visibilityWrites += isShown
                check(disposals == 0) { "A disposed window must never receive queued visibility updates" }
                isVisible = isShown
                isNativeVisible = isShown
            },
            enqueue = queue::addLast,
        )

        fun runQueued() {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }
    }
}
