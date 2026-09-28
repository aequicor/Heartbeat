package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StudioHistoryProjectionTest {
    @Test
    fun `protocol-only reasoning items do not create agent replies`() {
        val now = Instant.fromEpochSeconds(100)
        val items = listOf(
            SessionItem.Message(info("prompt", 0), MessageRole.User, listOf(ContentPart.Text("17 + 25"))),
            SessionItem.UnsupportedItem(info("reasoning", 1), "reasoning"),
            SessionItem.UnsupportedItem(info("other", 2), "futureProtocolItem"),
            SessionItem.Message(info("answer", 3), MessageRole.Assistant, listOf(ContentPart.Text("42"))),
        )
        assertEquals(
            listOf(
                StudioMessage.Prompt("prompt", now, "17 + 25"),
                StudioMessage.Reply("answer", now, "42", isStreaming = true),
            ),
            items.toStudioMessages(now, isRunning = true),
        )
    }

    @Test
    fun `user-visible engine notices remain in the transcript`() {
        val now = Instant.fromEpochSeconds(100)
        val notice = SessionItem.Notice(info("notice", 0), "The request was interrupted")
        assertEquals(
            listOf(StudioMessage.Reply("notice", now, "The request was interrupted")),
            listOf(notice).toStudioMessages(now, isRunning = false),
        )
    }

    private fun info(id: String, position: Long): ItemInfo = ItemInfo(ItemId(id), position, revision = 0)
}
