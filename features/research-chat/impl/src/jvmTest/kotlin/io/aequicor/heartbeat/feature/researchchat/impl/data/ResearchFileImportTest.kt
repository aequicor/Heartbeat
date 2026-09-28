package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResearchFileImportTest {
    @Test
    fun `text is decoded while image and PDF bytes retain their actual content`() {
        val text = encodeResearchFile("notes.md", "text/markdown", "# Источник".encodeToByteArray())
        assertEquals("# Источник", text.value)
        assertEquals(ResearchResourceKind.Document, text.kind)
        val image = encodeResearchFile("image.png", "image/png", byteArrayOf(1, 2, 3))
        assertEquals("data:image/png;base64,AQID", image.value)
        assertEquals(ResearchResourceKind.Image, image.kind)
        val pdf = encodeResearchFile("report.pdf", "application/pdf", "%PDF-1.7".encodeToByteArray())
        assertTrue(pdf.value.startsWith("data:application/pdf;base64,"))
        assertEquals(ResearchResourceKind.Document, pdf.kind)
    }

    @Test
    fun `empty oversized binary text and unsupported files are rejected`() {
        assertFailsWith<IllegalArgumentException> { encodeResearchFile("x.txt", "text/plain", byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> {
            encodeResearchFile(
                "x.txt",
                "text/plain",
                ByteArray(10 * 1024 * 1024 + 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            encodeResearchFile(
                "x.exe",
                "application/octet-stream",
                byteArrayOf(1),
            )
        }
        assertFailsWith<Exception> { encodeResearchFile("x.txt", "text/plain", byteArrayOf(0xff.toByte())) }
    }
}
