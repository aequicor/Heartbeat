@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_copy_surface
import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_create_surface
import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_resolve_region
import io.aequicor.heartbeat.platform.nativelauncher.windowsprocess.hb_resolve_surface
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LauncherCoverageTest {
    @Test
    fun roundedSkeletonKeepsItsMaskAndSmoothContinuousGradient() = memScoped {
        val dc = assertNotNull(platform.windows.CreateCompatibleDC(null))
        val data = alloc<COpaquePointerVar>()
        val bitmap = assertNotNull(hb_create_surface(dc, 320, 80, data.ptr))
        val previous = platform.windows.SelectObject(dc, bitmap)
        val canvas = LauncherCanvas()
        try {
            canvas.paint(dc, 320, 80) {
                fill(0, 0, 320, 80, 0x101010u)
                shimmer(40, 20, 240, 24, 0x202020u, 0x606060u, 160)
            }
            platform.windows.GdiFlush()
            val pixels = assertNotNull(data.value).reinterpret<UIntVar>()
            assertEquals(0x101010u, pixels[19 * 320 + 160])
            assertEquals(0x101010u, pixels[44 * 320 + 160])
            assertEquals(0x101010u, pixels[20 * 320 + 40])
            val samples = (48..272).map { (pixels[32 * 320 + it] and 255u).toInt() }
            assertTrue(samples.max() - samples.min() > 40)
            assertTrue(samples.distinct().size > 30)
            assertTrue(samples.all { it in 32..96 })
        } finally {
            canvas.close()
            platform.windows.SelectObject(dc, previous)
            platform.windows.DeleteObject(bitmap)
            platform.windows.DeleteDC(dc)
        }
    }

    @Test
    fun coverageBoundsBlendOnlyTheEdgeAndKeepSolidPixels() = memScoped {
        val source = allocArray<UIntVar>(32)
        repeat(32) { source[it] = 0x204080u }
        repeat(4) { row -> repeat(4) { column -> source[row * 8 + column] = if (row < 2) 0xFFFFFFu else 0u } }
        val result = allocArray<UIntVar>(2)
        hb_copy_surface(source, result, 2, 1, 2)
        assertEquals(0xFFFFFFu, result[0])
        assertEquals(0x204080u, result[1])
        hb_resolve_region(source, result, 2, 0, 0, 1, 1)
        assertEquals(0x808080u, result[0])
        assertEquals(0x204080u, result[1])
    }

    @Test
    fun halfCoveredPixelBlendsEdgesInsteadOfChoosingOneSubpixel() = memScoped {
        val source = allocArray<UIntVar>(16)
        repeat(16) { source[it] = if (it < 8) 0xFFFFFFu else 0u }
        val result = allocArray<UIntVar>(1)
        hb_resolve_surface(source, result, 1, 1, 1)
        assertEquals(0x808080u, result[0])
    }

    @Test
    fun retainedBufferStrideDoesNotLeakPaddingOrOverwriteOtherPixels() = memScoped {
        val source = allocArray<UIntVar>(64)
        repeat(64) { source[it] = 0xFFFFFFu }
        repeat(8) { row -> repeat(4) { column -> source[row * 8 + column] = 0x204080u } }
        val result = allocArray<UIntVar>(4)
        repeat(4) { result[it] = 0x123456u }
        hb_resolve_surface(source, result, 1, 2, 2)
        assertEquals(0x204080u, result[0])
        assertEquals(0x204080u, result[2])
        assertEquals(0x123456u, result[1])
        assertEquals(0x123456u, result[3])
    }
}
