package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoticeKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioRolloverTest {
    @Test
    fun `a rollover freezes the transcript once and closes the segment with a notice`() {
        val first = listOf(message("a", 0), message("b", 1)).frozen(1)
        val second = (first + message("c", 0)).frozen(2)

        assertEquals(listOf("hb.seg.1/a", "hb.seg.1/b", "hb.seg.1/rollover"), first.map { it.info.id.value })
        assertEquals(NoticeKind.ContextRollover, (first.last() as SessionItem.Notice).kind)
        assertEquals(
            listOf("hb.seg.1/a", "hb.seg.1/b", "hb.seg.1/rollover", "hb.seg.2/c", "hb.seg.2/rollover"),
            second.map { it.info.id.value },
        )
        assertTrue(second.all { it.isFrozenSegment })
    }

    @Test
    fun `the seed carries only the essence of the session that ends`() {
        val items = listOf(message("old", 0, "Frozen text")).frozen(1) + message("new", 0, "Fix the parser")

        val seed = rolloverSeed(items)

        assertTrue("Fix the parser" in seed && "Frozen text" !in seed)
        assertFalse("Earlier conversation" in rolloverSeed(listOf(message("old", 0)).frozen(1)))
    }

    @Test
    fun `helpers, organisms and chats without a session never roll over`() {
        val chat = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, ref = Ref)

        assertTrue(chat.isRolloverAllowed)
        assertFalse(chat.copy(ref = null).isRolloverAllowed)
        assertFalse(chat.copy(organismId = "organism").isRolloverAllowed)
        assertTrue(chat.copy(ref = null, previousRefs = listOf(Ref)).owns(Ref))
    }

    private fun message(id: String, position: Long, text: String = id) = SessionItem.Message(
        ItemInfo(ItemId(id), position, 0),
        MessageRole.User,
        listOf(ContentPart.Text(text)),
    )

    private companion object {
        val Ref = SessionRef(EngineId("codex"), SessionSourceId("s"), "thread")
    }
}
