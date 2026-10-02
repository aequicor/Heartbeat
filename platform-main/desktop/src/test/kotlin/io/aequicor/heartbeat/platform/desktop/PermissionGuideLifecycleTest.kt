package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PermissionGuideLifecycleTest {
    @Test
    fun `target and native setup failures only omit optional guidance`() {
        assertNull(permissionGuideOrNull("resolve the target") { error("process lookup failed") })
        assertNull(permissionGuideOrNull("initialize Cocoa") { throw UnsatisfiedLinkError("framework missing") })
    }

    @Test
    fun `optional guidance never swallows cancellation`() {
        val cancelled = CancellationException("window closed")
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> { permissionGuideOrNull("load") { throw cancelled } },
        )
    }

    @Test
    fun `bounds failure hides the panel and stops only its tracker`() = runTest {
        val visibility = mutableListOf<Boolean>()
        var polls = 0
        val tracking = launch {
            followSettingsWindow(
                bounds = { if (polls++ == 0) SETTINGS else error("window server failed") },
                place = {},
                onShown = visibility::add,
            )
        }
        runCurrent()
        assertEquals(listOf(true), visibility)
        advanceTimeBy(TRACKING_MS)
        runCurrent()
        assertTrue(tracking.isCompleted)
        assertFalse(tracking.isCancelled)
        assertEquals(listOf(true, false), visibility)
        assertEquals(2, polls)
    }

    @Test
    fun `native linkage failure in tracking does not cancel its parent`() = runTest {
        val visibility = mutableListOf<Boolean>()
        followSettingsWindow(
            bounds = { throw UnsatisfiedLinkError("window server unavailable") },
            place = {},
            onShown = visibility::add,
        )
        assertEquals(listOf(false), visibility)
    }

    @Test
    fun `tracking cancellation propagates`() = runTest {
        val cancelled = CancellationException("guide dismissed")
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                followSettingsWindow(bounds = { throw cancelled }, place = {}, onShown = {})
            },
        )
    }

    @Test
    fun `closed settings hide the panel without resurrecting the initial fallback`() = runTest {
        val visibility = mutableListOf<Boolean>()
        var settings: Rectangle? = SETTINGS
        val tracking = launch {
            followSettingsWindow(bounds = { settings }, place = {}, onShown = visibility::add)
        }
        runCurrent()
        settings = null
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(visibility.first())
        assertTrue(visibility.drop(1).all { !it })
        tracking.cancelAndJoin()
    }

    @Test
    fun `placement cannot reveal a guide registered during capture`() {
        val native = FakePresentation()
        val guide = PermissionGuidePresentation(native, native::dispose)
        val lease = guide.suppress()
        guide.place(true)
        assertFalse(guide.isShown)
        assertTrue(native.isSuppressed)
        lease.close()
        assertTrue(guide.isShown)
        assertFalse(native.isSuppressed)
        guide.close()
    }

    @Test
    fun `placement visibility waits for all active suppression leases`() {
        val native = FakePresentation()
        val guide = PermissionGuidePresentation(native, native::dispose)
        guide.place(true)
        val first = guide.suppress()
        val second = guide.suppress()
        guide.place(false)
        assertTrue(guide.isShown)
        first.close()
        first.close()
        assertTrue(guide.isShown)
        assertTrue(native.isSuppressed)
        second.close()
        assertFalse(guide.isShown)
        assertFalse(native.isSuppressed)
        guide.close()
    }

    @Test
    fun `dismissing a suppressed guide prevents restoration`() {
        val native = FakePresentation()
        val guide = PermissionGuidePresentation(native, native::dispose)
        guide.place(true)
        val lease = guide.suppress()
        guide.close()
        guide.close()
        guide.place(true)
        lease.close()
        assertFalse(guide.isShown)
        assertEquals(1, native.disposals)
        assertEquals(0, native.restorations)
    }

    @Test
    fun `failed native suppression leaves placement responsive`() {
        val guide = PermissionGuidePresentation(ComputerUsePresentation { error("could not hide") }, {})
        guide.place(true)
        assertFailsWith<IllegalStateException> { guide.suppress() }
        assertTrue(guide.isShown)
        guide.place(false)
        assertFalse(guide.isShown)
        guide.close()
    }

    private class FakePresentation : ComputerUsePresentation {
        private var count = 0
        var disposals = 0
        var restorations = 0
        val isSuppressed get() = count > 0

        override fun suppress(): AutoCloseable {
            count++
            return AutoCloseable {
                count--
                if (disposals == 0 && count == 0) restorations++
            }
        }

        fun dispose() {
            disposals++
        }
    }

    private companion object {
        val SETTINGS = Rectangle(100, 100, 800, 600)
        const val TRACKING_MS = 250L
    }
}
