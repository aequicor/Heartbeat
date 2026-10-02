package io.aequicor.heartbeat.feature.attachments.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentPickerFiltersTest {
    @Test
    fun `the dialog mask excludes formats unsupported by the exact model`() {
        val support = PromptInputSupport(
            imageMediaTypes = setOf("image/jpeg", "image/png"),
            resourceMediaTypes = setOf("text/markdown"),
        )
        assertEquals(listOf("jpg", "jpeg", "png", "md", "markdown"), attachmentExtensions(support))
        assertEquals(emptyList(), attachmentExtensions(PromptInputSupport()))
    }

    @Test
    fun `the save dialog offers the attachment's own extension`() {
        assertEquals(listOf("pdf"), saveExtensions("Отчёт.pdf"))
        assertEquals(listOf("gz"), saveExtensions("archive.tar.gz"))
        assertEquals(emptyList(), saveExtensions("LICENSE"))
        assertEquals(emptyList(), saveExtensions(""))
    }
}
