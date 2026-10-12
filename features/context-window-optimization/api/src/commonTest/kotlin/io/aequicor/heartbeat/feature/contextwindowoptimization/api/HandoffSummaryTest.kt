package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostDirective
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HandoffSummaryTest {
    private var position = 0L

    @Test
    fun `the newest handoff turn splits off the agent's answer`() {
        val work = listOf(message(MessageRole.User, "Task"), message(MessageRole.Assistant, "Working"))
        val items = work + message(MessageRole.User, withHostDirectives("", listOf(HANDOFF_SUMMARY_PROMPT))) +
            message(MessageRole.Assistant, "Draft") + message(MessageRole.Assistant, "Decided to use Ktor")

        val split = handoffSplit(items)

        assertEquals(work, split.items)
        assertEquals("Decided to use Ktor", split.summary)
    }

    @Test
    fun `without an answer or a handoff turn there is no summary`() {
        val work = listOf(message(MessageRole.User, "Task"))

        assertEquals(HandoffSplit(work, null), handoffSplit(work))
        assertNull(handoffSplit(work + message(MessageRole.User, hostDirective(HANDOFF_SUMMARY_PROMPT))).summary)
    }

    @Test
    fun `text the user typed never counts as a handoff turn`() {
        val items = listOf(message(MessageRole.User, HANDOFF_SUMMARY_PROMPT), message(MessageRole.Assistant, "Reply"))

        val split = handoffSplit(items)

        assertEquals(items, split.items)
        assertNull(split.summary)
    }

    @Test
    fun `summary text never reaches toString`() {
        val items = listOf(
            message(MessageRole.User, hostDirective(HANDOFF_SUMMARY_PROMPT)),
            message(MessageRole.Assistant, "secret"),
        )

        assertFalse("secret" in handoffSplit(items).toString())
    }

    private fun message(role: MessageRole, text: String) =
        SessionItem.Message(ItemInfo(ItemId("i$position"), position++, 0), role, listOf(ContentPart.Text(text)))
}
