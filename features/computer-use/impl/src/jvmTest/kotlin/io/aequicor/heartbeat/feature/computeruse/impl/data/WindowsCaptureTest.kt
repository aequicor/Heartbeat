package io.aequicor.heartbeat.feature.computeruse.impl.data

import com.sun.jna.Pointer
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsCaptureTest {
    @Test
    fun `RGB bitmap is created from display DC and deselected before reading`() {
        val fixture = Fixture()
        val pixels = assertNotNull(captureWindowsBitmap(fixture.users, fixture.gdi, fixture.window))
        assertEquals(0xFF332211.toInt(), pixels.pixel(0, 0))
        assertEquals(
            listOf(
                "getDisplay",
                "createMemory",
                "createColorBitmap",
                "selectBitmap",
                "render",
                "restore",
                "read",
                "deleteBitmap",
                "deleteMemory",
                "releaseDisplay",
            ),
            fixture.calls,
        )
    }

    @Test
    fun `failed rendering releases every native resource`() {
        val fixture = Fixture(isRendered = false)
        assertNull(captureWindowsBitmap(fixture.users, fixture.gdi, fixture.window))
        assertTrue(fixture.calls.takeLast(4) == listOf("restore", "deleteBitmap", "deleteMemory", "releaseDisplay"))
    }

    @Test
    fun `minimized windows are refused before allocating resources`() {
        val fixture = Fixture(isMinimized = true)
        assertNull(captureWindowsBitmap(fixture.users, fixture.gdi, fixture.window))
        assertTrue(fixture.calls.isEmpty())
    }

    private class Fixture(isRendered: Boolean = true, isMinimized: Boolean = false) {
        val calls = mutableListOf<String>()
        val window = Pointer.createConstant(1)
        private val display = Pointer.createConstant(2)
        private val memory = Pointer.createConstant(3)
        private val bitmap = Pointer.createConstant(4)
        private val previous = Pointer.createConstant(5)
        private var selected: Pointer = previous

        val users: User32Lib = proxy(User32Lib::class.java) { name, arguments ->
            when (name) {
                "IsIconic" -> isMinimized

                "GetWindowRect" -> {
                    val rect = arguments[1] as NativeRect
                    rect.right = 2
                    rect.bottom = 1
                    true
                }

                "GetDC" -> {
                    calls += "getDisplay"
                    display
                }

                "ReleaseDC" -> {
                    assertEquals(display, arguments[1])
                    calls += "releaseDisplay"
                    1
                }

                "PrintWindow" -> {
                    assertEquals(bitmap, selected)
                    calls += "render"
                    isRendered
                }

                else -> error("Unexpected user32 call: $name")
            }
        }

        val gdi: Gdi32Lib = proxy(Gdi32Lib::class.java) { name, arguments ->
            when (name) {
                "CreateCompatibleDC" -> {
                    assertEquals(display, arguments[0])
                    calls += "createMemory"
                    memory
                }

                "CreateCompatibleBitmap" -> {
                    assertEquals(display, arguments[0])
                    calls += "createColorBitmap"
                    bitmap
                }

                "SelectObject" -> {
                    assertEquals(memory, arguments[0])
                    val old = selected
                    selected = arguments[1] as Pointer
                    calls += if (selected == bitmap) "selectBitmap" else "restore"
                    old
                }

                "GetDIBits" -> {
                    assertEquals(previous, selected, "the captured bitmap must be deselected before GetDIBits")
                    assertEquals(display, arguments[0])
                    val bytes = arguments[4] as ByteArray
                    bytes[0] = 0x11
                    bytes[1] = 0x22
                    bytes[2] = 0x33
                    calls += "read"
                    1
                }

                "DeleteObject" -> {
                    calls += "deleteBitmap"
                    true
                }

                "DeleteDC" -> {
                    calls += "deleteMemory"
                    true
                }

                else -> error("Unexpected gdi32 call: $name")
            }
        }
    }

    private companion object {
        @Suppress("UNCHECKED_CAST") // a test proxy implements exactly the supplied interface
        fun <T> proxy(type: Class<T>, invoke: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { _, method, arguments -> invoke(method.name, arguments.orEmpty()) } as T
    }
}
