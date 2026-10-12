package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioContextCarryTest {
    private var record = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, pendingSeed = "seed")

    @Test
    fun `a native compaction leaves the brief for the next turn, which consumes it with the seed`() = runTest {
        carry { listOf(user("Fix the parser")) }.brief("chat")

        assertTrue("[1] Fix the parser" in record.pendingBrief.orEmpty())
        record = record.seeded()
        assertNull(record.pendingSeed)
        assertNull(record.pendingBrief)
    }

    @Test
    fun `a brief that cannot be built leaves the compaction successful`() = runTest {
        carry { error("transcript unavailable") }.brief("chat")

        assertNull(record.pendingBrief)
        assertEquals("seed", record.pendingSeed)
    }

    private fun carry(items: suspend (String) -> List<SessionItem>) = StudioContextCarry(
        StudioConversationLocks(Mutex()),
        record = { record },
        items = items,
        write = { _, _ -> },
        update = { _, change -> record = record.change() },
        detach = {},
    )

    private fun user(text: String) =
        SessionItem.Message(ItemInfo(ItemId("u"), 0, 0), MessageRole.User, listOf(ContentPart.Text(text)))
}
