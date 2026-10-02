package io.aequicor.heartbeat.feature.computeruse.impl.data

import com.sun.jna.Memory
import com.sun.jna.Pointer
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MacClientGeometryTest {
    @Test
    fun `missing accessibility permission never tries to infer a title inset`() {
        val ax = nativeProxy(ApplicationServicesLib::class.java) { name ->
            check(name == "AXIsProcessTrusted")
            false
        }
        val cf = nativeProxy(CoreFoundationLib::class.java) { error("No AX access allowed") }
        assertNull(MacClientGeometry(ax, cf).bounds(123, ScreenBounds(0, 0, 400, 300)))
    }

    @Test
    fun `unavailable AX window data is refused and owned native objects are released`() {
        var releases = 0
        Memory(8).use { memory ->
            val ax = nativeProxy(ApplicationServicesLib::class.java) { name ->
                when (name) {
                    "AXIsProcessTrusted" -> true
                    "AXUIElementCreateApplication" -> Pointer(42L)
                    "AXUIElementSetMessagingTimeout" -> 0
                    "AXUIElementCopyAttributeValue" -> -25212
                    else -> error("Unexpected AX call $name")
                }
            }
            val cf = nativeProxy(CoreFoundationLib::class.java) { name ->
                when (name) {
                    "CFStringCreateWithCString" -> memory

                    "CFRelease" -> {
                        releases++
                        null
                    }

                    else -> error("Unexpected CF call $name")
                }
            }
            assertNull(MacClientGeometry(ax, cf).bounds(123, ScreenBounds(0, 0, 400, 300)))
            assertEquals(2, releases)
        }
    }

    private fun <T> nativeProxy(type: Class<T>, call: (String) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> call(method.name) },
    )

    @Test
    fun `client crop maps Retina points at negative monitor origins`() {
        val pixels = PixelGrid(200, 160, IntArray(200 * 160) { it })
        val window = ScreenBounds(-300, -100, 100, 80)
        val client = ScreenBounds(-300, -80, 100, 60)
        val cropped = assertNotNull(cropClientPixels(pixels, window, client))
        assertEquals(200, cropped.widthPx)
        assertEquals(120, cropped.heightPx)
        assertEquals(pixels.pixel(0, 40), cropped.pixel(0, 0))
    }

    @Test
    fun `partial content widgets and geometry outside the window are refused`() {
        val pixels = PixelGrid(100, 80, IntArray(8000))
        val window = ScreenBounds(0, 0, 100, 80)
        assertNull(cropClientPixels(pixels, window, ScreenBounds(20, 20, 80, 60)))
        assertNull(cropClientPixels(pixels, window, ScreenBounds(0, -10, 100, 90)))
    }
}
