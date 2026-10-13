package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoticeKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_REQUEST_PREFIX
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_SUMMARY_PROMPT
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
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
    fun `the continuation seed keeps user messages of every segment and the last turn of the current one`() {
        val earlier = listOf(message("task", 0, "Fix every open issue"), reply("old", 1, "Old work")).frozen(1)
        val items = earlier + message("next", 0, "Skip #7") + reply("now", 1, "Fixing #8")

        val seed = continuationSeed(items, isSummarized = false)

        assertTrue("[1] Fix every open issue" in seed && "[2] Skip #7" in seed)
        assertTrue("Assistant: Fixing #8" in seed)
        assertFalse("Old work" in seed)
    }

    @Test
    fun `the continuation seed takes the handoff summary and the turn before it`() {
        val handoff = message("handoff", 2, withHostDirectives("", listOf(HANDOFF_SUMMARY_PROMPT)))
        val items = listOf(message("task", 0, "Fix the parser"), reply("work", 1, "Fixing the lexer"), handoff) +
            reply("summary", 3, "Decided to rewrite the lexer")

        val seed = continuationSeed(items, isSummarized = true)

        assertTrue("## Work so far\nDecided to rewrite the lexer" in seed)
        assertFalse("Decided" in continuationSeed(items, isSummarized = false))
        assertTrue("Assistant: Fixing the lexer" in seed.substringAfter("## Last turn"))
        assertFalse("handoff-summary" in seed)
    }

    @Test
    fun `the brief after a native compaction keeps every user message without a work summary`() {
        val items = listOf(message("task", 0, "Fix the parser"), reply("work", 1, "Fixed the lexer")).frozen(1) +
            message("next", 0, "Now the tests") + reply("now", 1, "Running tests")

        val brief = compactionBrief(items)

        assertTrue("[1] Fix the parser" in brief && "[2] Now the tests" in brief)
        assertTrue("Assistant: Running tests" in brief)
        assertFalse("## Work so far" in brief)
    }

    @Test
    fun `the next turn carries the seed and brief a compaction none and a handoff turn no brief`() {
        val chat = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, pendingSeed = "seed", pendingBrief = "brief")
        val turn = StudioTurnRequest("chat", "prompt", DefaultRunSettings, WorktreeRunKind.Coding, RequestId("r"))

        assertEquals(listOf("seed", "brief"), chat.carriedDirectives(turn))
        assertEquals(emptyList(), chat.carriedDirectives(turn.copy(compaction = ContextCompaction())))
        assertEquals(
            listOf("seed"),
            chat.carriedDirectives(turn.copy(request = RequestId("${HANDOFF_REQUEST_PREFIX}1"))),
        )
        assertEquals(null, chat.copy(ref = Ref).rolledOver("next").pendingBrief)
    }

    @Test
    fun `helpers organisms and chats without a session never roll over`() {
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

    private fun reply(id: String, position: Long, text: String) = SessionItem.Message(
        ItemInfo(ItemId(id), position, 0),
        MessageRole.Assistant,
        listOf(ContentPart.Text(text)),
    )

    private companion object {
        val Ref = SessionRef(EngineId("codex"), SessionSourceId("s"), "thread")
    }
}
