package io.aequicor.heartbeat.core.desktopdialogs

import com.sun.jna.Native
import com.sun.jna.WString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogFiltersTest {
    @Test
    fun `the explorer mask lists every extension`() {
        assertEquals("*.png;*.jpg;*.jpeg", windowsFileMask(listOf("png", "jpg", "jpeg")))
        assertEquals("", windowsFileMask(emptyList()))
    }

    @Test
    fun `panel filters match names case-insensitively and accept everything without extensions`() {
        assertTrue(matchesExtensions("photo.PNG", listOf("png")))
        assertTrue(matchesExtensions("Документ 1.pdf", listOf("pdf")))
        assertTrue(matchesExtensions("archive.tar.gz", listOf("gz")))
        assertTrue(matchesExtensions("notes.md", emptyList()))
        assertFalse(matchesExtensions("notes.md", listOf("png")))
        assertFalse(matchesExtensions("pngfile", listOf("png")))
    }

    @Test
    fun `COMDLG_FILTERSPEC keeps two pointer-sized wide strings`() {
        assertEquals(2 * Native.POINTER_SIZE, ComdlgFilterSpec().size())
        val filter = ComdlgFilterSpec().apply {
            name = WString("*.png;*.jpg")
            spec = WString("*.png;*.jpg")
        }
        filter.write()
        assertEquals("*.png;*.jpg", filter.pointer.getPointer(0)?.getWideString(0))
        assertEquals(
            "*.png;*.jpg",
            filter.pointer.getPointer(Native.POINTER_SIZE.toLong())?.getWideString(0),
        )
    }
}
