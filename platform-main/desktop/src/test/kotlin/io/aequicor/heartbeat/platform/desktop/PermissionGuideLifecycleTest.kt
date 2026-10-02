package io.aequicor.heartbeat.platform.desktop

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

    private companion object {
        val SETTINGS = Rectangle(100, 100, 800, 600)
        const val TRACKING_MS = 250L
    }
}
