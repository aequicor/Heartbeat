package io.aequicor.heartbeat.feature.computeruse.impl.data

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Marshals the `SendInput` payload without calling user32, so it runs on every host. */
class WindowsTextBackendTest {
    @Test
    fun `events share one contiguous block of native INPUT records`() {
        val events = unicodeEvents("aЖ")
        assertEquals(4, events.size)
        val size = events.first().size()
        assertEquals(if (Native.POINTER_SIZE == 8) 40 else 28, size)
        val base = Pointer.nativeValue(events.first().pointer)
        events.forEachIndexed { index, event ->
            assertEquals(base + index.toLong() * size, Pointer.nativeValue(event.pointer), "event=$index")
        }
        // JNA runs this check before passing a Structure[] to native code; it throws for scattered elements.
        Structure.autoWrite(events)
    }

    @Test
    fun `every code unit is a unicode press followed by its release`() {
        val text = "a🙂"
        val events = unicodeEvents(text)
        Structure.autoWrite(events)
        for (event in events) {
            event.type = 0
            event.input.keyboard.wScan = 0
            event.input.keyboard.dwFlags = 0
            event.read()
        }
        assertEquals(List(events.size) { 1 }, events.map { it.type })
        val scans = events.map { it.input.keyboard.wScan }
        assertEquals(text.flatMap { listOf(it.code.toShort(), it.code.toShort()) }, scans)
        val flags = events.map { it.input.keyboard.dwFlags }
        assertEquals(List(text.length) { listOf(UNICODE, UNICODE or KEY_UP) }.flatten(), flags)
        assertTrue(events.all { it.input.keyboard.wVk == 0.toShort() })
    }

    @Test
    fun `empty text has no events`() {
        assertTrue(unicodeEvents("").isEmpty())
    }

    private companion object {
        const val UNICODE = 0x0004
        const val KEY_UP = 0x0002
    }
}
