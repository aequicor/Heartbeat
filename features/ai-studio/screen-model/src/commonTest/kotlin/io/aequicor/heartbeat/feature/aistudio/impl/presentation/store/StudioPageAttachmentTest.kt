package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.NativeAttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import io.aequicor.heartbeat.feature.aistudio.screenmodel.document
import io.aequicor.heartbeat.feature.aistudio.screenmodel.fileName
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isAttachmentPending
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isValid
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.predict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioPageAttachmentTest {
    @Test
    fun `page survives serialization with source selection unicode and safe filename`() {
        val page = NativeAttachmentUi.Page("https://example.com/doc", "Page:/\"", "Текст 🦊", "Выделение")
        val intent = AiStudioScreenIntent.ImportAttachments(0, listOf(page))
        val restored = StudioViewJson.decodeFromString(
            AiStudioScreenIntent.serializer(),
            StudioViewJson.encodeToString(AiStudioScreenIntent.serializer(), intent),
        )
        assertEquals(intent, restored)
        assertEquals("Page.md", page.fileName())
        assertTrue(page.document().contains("Source: https://example.com/doc"))
        assertTrue(page.document().contains("## Selection\n\nВыделение"))
        assertTrue(page.document().contains("## Page text\n\nТекст 🦊"))
        assertTrue(page.isValid())
        assertFalse(page.copy(url = "file:///secret").isValid())
        assertFalse(page.copy(text = "x".repeat(200_001)).isValid())
        assertFalse(page.copy(selection = "x".repeat(20_001)).isValid())
        assertFalse(page.copy(title = "x".repeat(513)).isValid())
        val predicted = AiStudioScreenState().predict(intent)
        assertTrue(predicted.isAttachmentPending(0))
        assertEquals(predicted, predicted.predict(intent))
    }
}
