package io.aequicor.heartbeat.feature.attachments.impl.presentation.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentTextPreviewTest {
    @Test
    fun `a small UTF-8 document is shown completely`() {
        val text = "Текст исследовательского документа"
        val preview = text.encodeToByteArray().toTextPreview()
        assertEquals(text, preview.text)
        assertFalse(preview.isTruncated)
    }

    @Test
    fun `a ten MiB document has a bounded preview and retains its original export bytes`() {
        val original = ByteArray(10 * 1024 * 1024) { 'A'.code.toByte() }
        val preview = original.toTextPreview()
        assertEquals(64 * 1024, preview.text.length)
        assertTrue(preview.isTruncated)
        assertEquals(10 * 1024 * 1024, original.size)
        assertTrue(original.all { it == 'A'.code.toByte() })
    }
}
