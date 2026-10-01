package io.aequicor.heartbeat.feature.attachments.impl.data

import com.sun.jna.Native
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WindowsAttachmentPickerTest {
    @Test
    fun `native mask excludes formats unsupported by the exact model`() {
        val mask = windowsAttachmentPatterns(
            PromptInputSupport(
                imageMediaTypes = setOf("image/jpeg", "image/png"),
                resourceMediaTypes = setOf("text/markdown"),
            ),
        )
        assertEquals("*.jpg;*.jpeg;*.png;*.md;*.markdown", mask)
        assertFalse(mask.contains("*.*"))
        assertFalse(mask.contains("pdf"))
        assertEquals("", windowsAttachmentPatterns(PromptInputSupport()))
    }

    @Test
    fun `single and multiple UTF16 paths preserve spaces and Unicode`() {
        assertEquals(
            listOf("C:\\Research\\Документ 1.pdf"),
            parseWindowsAttachmentSelection("C:\\Research\\Документ 1.pdf\u0000\u0000"),
        )
        assertEquals(
            listOf("C:\\Research\\Документ 1.pdf", "C:\\Research\\photo 2.jpg"),
            parseWindowsAttachmentSelection("C:\\Research\u0000Документ 1.pdf\u0000photo 2.jpg\u0000\u0000"),
        )
        assertEquals(
            listOf("\\\\server\\share\\notes.md"),
            parseWindowsAttachmentSelection("\\\\server\\share\u0000notes.md\u0000\u0000"),
        )
        assertEquals(emptyList(), parseWindowsAttachmentSelection("\u0000\u0000"))
    }

    @Test
    fun `malformed multiple selection cannot escape its native directory`() {
        assertFailsWith<IllegalArgumentException> {
            parseWindowsAttachmentSelection(
                "C:\\Research\u0000..\\notes.md\u0000\u0000",
            )
        }
    }

    @Test
    fun `OPENFILENAMEW layout matches desktop Windows pointer width`() {
        assertEquals(if (Native.POINTER_SIZE == 8) 152 else 88, WindowsOpenFileName().size())
    }
}
