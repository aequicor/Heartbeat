package io.aequicor.heartbeat.feature.attachments.impl.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentMediaTypeTest {
    @Test
    fun `source files the composer suggests are importable as plain text`() {
        assertEquals("text/plain", attachmentMediaType("Main.kt"))
        assertEquals("text/plain", attachmentMediaType("build.gradle.kts"))
        assertEquals("text/markdown", attachmentMediaType("README.md"))
        assertEquals("image/png", attachmentMediaType("shot.PNG"))
    }

    @Test
    fun `an unknown extension stays an opaque binary`() {
        assertEquals("application/octet-stream", attachmentMediaType("archive.bin"))
        assertEquals("application/octet-stream", attachmentMediaType("LICENSE"))
    }
}
