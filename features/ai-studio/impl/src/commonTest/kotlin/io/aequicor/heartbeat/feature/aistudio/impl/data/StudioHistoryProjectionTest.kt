package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.aistudio.impl.domain.LearningAction
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioLearningCall
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioReplyPart
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus
import io.aequicor.heartbeat.feature.feedback.api.FeedbackAnchor
import io.aequicor.heartbeat.feature.feedback.api.FeedbackChange
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutcome
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioHistoryProjectionTest {
    private val now = Instant.fromEpochSeconds(100)

    private fun feedback(after: String?, id: String = "operation", outcome: FeedbackOutcome = FeedbackOutcome.Pending) =
        FeedbackRecord(
            id,
            "chat",
            0,
            now,
            FeedbackAnchor(after = after?.let(::ItemId)),
            FeedbackChange.Effort(null, "high"),
            outcome,
        )

    @Test
    fun `user attachment cards retain original references without MIME labels in the visible prompt`() {
        val image = ResourceRef("attachment:image", "image/png")
        val document = ResourceRef("attachment:document", "text/markdown")
        val item = SessionItem.Message(
            info("prompt", 0),
            MessageRole.User,
            listOf(ContentPart.Text("Compare these files"), ContentPart.Image(image), ContentPart.Resource(document)),
        )
        val prompt = assertIs<StudioMessage.Prompt>(listOf(item).toStudioMessages(now, false).single())
        assertEquals("Compare these files", prompt.text)
        assertEquals(listOf(image, document), prompt.attachments)
        val attachmentOnly = item.copy(parts = listOf(ContentPart.Image(image)))
        val projected = assertIs<StudioMessage.Prompt>(listOf(attachmentOnly).toStudioMessages(now, false).single())
        assertEquals("", projected.text)
        assertEquals(listOf(image), projected.attachments)
    }

    @Test
    fun `host directives sent with a prompt stay out of the transcript`() {
        val item = SessionItem.Message(
            info("prompt", 0),
            MessageRole.User,
            listOf(ContentPart.Text(withHostDirectives("/remember Use UTF-8", listOf("Call remember now")))),
        )
        val prompt = assertIs<StudioMessage.Prompt>(listOf(item).toStudioMessages(now, false).single())
        assertEquals("/remember Use UTF-8", prompt.text)
    }

    @Test
    fun `feedback separates native answers and never claims to stream`() {
        val items = listOf(
            message("before", 0, MessageRole.Assistant, "Before", "turn"),
            message("after", 1, MessageRole.Assistant, "After", "turn"),
        )
        val result = items.toStudioMessages(now, true, listOf(feedback("before")))
        assertEquals(listOf("before", "feedback:operation", "after"), result.map { it.id })
        assertFalse(assertIs<StudioMessage.Reply>(result[1]).isStreaming)
        assertTrue(assertIs<StudioMessage.Reply>(result[2]).isStreaming)
        assertEquals("feedback", assertIs<StudioMessage.Reply>(result[1]).tools.single().title)
    }

    @Test
    fun `feedback before native history stays in place when the first items arrive`() {
        val record = feedback(null)
        assertEquals(
            listOf("feedback:operation"),
            emptyList<SessionItem>().toStudioMessages(now, false, listOf(record)).map { it.id },
        )
        val result = listOf(
            message("prompt", 0, MessageRole.User, "Start"),
            message("answer", 1, MessageRole.Assistant, "Working"),
        )
            .toStudioMessages(now, true, listOf(record))
        assertEquals(listOf("feedback:operation", "prompt", "answer"), result.map { it.id })
        assertFalse(assertIs<StudioMessage.Reply>(result.first()).isStreaming)
        assertTrue(assertIs<StudioMessage.Reply>(result.last()).isStreaming)
    }

    @Test
    fun `feedback after the latest native answer preserves that answer streaming status`() {
        val result = listOf(message("answer", 0, MessageRole.Assistant, "Working"))
            .toStudioMessages(now, true, listOf(feedback("answer")))
        assertTrue(assertIs<StudioMessage.Reply>(result.first()).isStreaming)
        assertFalse(assertIs<StudioMessage.Reply>(result.last()).isStreaming)
    }

    @Test
    fun `late native tool results still update the invocation before feedback`() {
        val call = ToolCallId("call")
        val items = listOf(
            SessionItem.ToolCall(info("invocation", 0, "turn"), call, "Command", "", ToolCallStatus.Running),
            SessionItem.ToolResult(info("result", 1, "turn"), call, listOf(ContentPart.Text("done"))),
        )
        val result = items.toStudioMessages(now, false, listOf(feedback("invocation")))
        assertEquals(2, result.size)
        assertEquals("done", assertIs<StudioMessage.Reply>(result[0]).tools.single().output)
        assertEquals(ToolRunStatus.Done, assertIs<StudioMessage.Reply>(result[0]).tools.single().status)
    }

    @Test
    fun `learning tool calls become cards that keep their arguments when the result arrives`() {
        val call = ToolCallId("remember")
        val arguments = """{"kind":"general","title":"UTF-8","content":"Run chcp 65001","safety":"safe"}"""
        val items = listOf(
            SessionItem.ToolCall(
                info("invocation", 0, "turn"),
                call,
                "mcp__heartbeat_tools__remember",
                arguments,
                ToolCallStatus.Running,
            ),
            SessionItem.ToolResult(info("result", 1, "turn"), call, listOf(ContentPart.Text("Saved."))),
        )
        val tool = assertIs<StudioMessage.Reply>(items.toStudioMessages(now, false).single()).tools.single()
        assertEquals(
            StudioLearningCall(LearningAction.Remember, InstructionKind.General, "UTF-8", "Run chcp 65001"),
            tool.learning,
        )
        assertEquals("Saved.", tool.output)

        val other = SessionItem.ToolCall(
            info("other", 0, "turn"),
            ToolCallId("x"),
            "remember_me",
            "{}",
            ToolCallStatus.Running,
        )
        assertEquals(
            null,
            assertIs<StudioMessage.Reply>(listOf(other).toStudioMessages(now, false).single()).tools.single().learning,
        )
    }

    @Test
    fun `a full history rewrite retains feedback with a missing anchor`() {
        val completed = feedback(
            "removed",
            outcome = FeedbackOutcome.Applied(SessionConfiguration(ModelId("model"), "high")),
        )
        val result = listOf(message("replacement", 0, MessageRole.Assistant, "New history"))
            .toStudioMessages(now, true, listOf(completed))
        assertEquals(listOf("replacement", "feedback:operation"), result.map { it.id })
        assertFalse(assertIs<StudioMessage.Reply>(result.last()).isStreaming)
        assertEquals(ToolRunStatus.Done, assertIs<StudioMessage.Reply>(result.last()).tools.single().status)
        assertEquals(1, emptyList<SessionItem>().toStudioMessages(now, false, listOf(completed)).size)
    }

    @Test
    fun `protocol-only reasoning items do not create agent replies`() {
        val items = listOf(
            message("prompt", 0, MessageRole.User, "17 + 25"),
            SessionItem.UnsupportedItem(info("reasoning", 1), "reasoning"),
            SessionItem.UnsupportedItem(info("other", 2), "futureProtocolItem"),
            message("answer", 3, MessageRole.Assistant, "42"),
        )
        val projected = items.toStudioMessages(now, isRunning = true)
        assertEquals(2, projected.size)
        assertEquals("17 + 25", assertIs<StudioMessage.Prompt>(projected[0]).text)
        val reply = assertIs<StudioMessage.Reply>(projected[1])
        assertEquals("42", reply.text)
        assertTrue(reply.isStreaming)
        assertFalse(reply.isTimestampKnown)
        assertFalse(projected[0].isTimestampKnown)
        assertTrue(reply.parts.none { it is StudioReplyPart.Reasoning })
    }

    @Test
    fun `engine exposed reasoning and tools remain between text parts in one turn`() {
        val call = ToolCallId("read-files")
        val items = listOf(
            message("prompt", 0, MessageRole.User, "Review"),
            message("intro", 1, MessageRole.Assistant, "I will inspect the files"),
            SessionItem.Message(
                info("thought", 2),
                MessageRole.Assistant,
                listOf(ContentPart.Reasoning("Compare the public API")),
            ),
            SessionItem.ToolCall(info("call", 3), call, "Read files", "src/Main.kt", ToolCallStatus.Running),
            message("progress", 4, MessageRole.Assistant, "The main entry is small"),
            SessionItem.ToolResult(info("result", 5), call, listOf(ContentPart.Text("class Main"))),
            message("final", 6, MessageRole.Assistant, "Here is the review"),
        )
        val projected = items.toStudioMessages(now, isRunning = false)
        assertEquals(2, projected.size)
        val reply = assertIs<StudioMessage.Reply>(projected[1])
        assertEquals(listOf("intro:0", "thought:0", "read-files", "progress:0", "final:0"), reply.parts.map { it.id })
        assertEquals("Compare the public API", assertIs<StudioReplyPart.Reasoning>(reply.parts[1]).text)
        val tool = assertIs<StudioReplyPart.Tool>(reply.parts[2]).tool
        assertEquals("Read files", tool.title)
        assertEquals(ToolRunStatus.Done, tool.status)
        assertEquals("src/Main.kt\nclass Main", tool.output)
        assertEquals(listOf(tool), reply.tools)
        assertFalse(reply.isStreaming)
    }

    @Test
    fun `distinct turns and user prompts never merge and only current answer streams`() {
        val items = listOf(
            message("first", 0, MessageRole.Assistant, "First", "a"),
            message("second", 1, MessageRole.Assistant, "Second", "b"),
            message("prompt", 2, MessageRole.User, "Again", "c"),
            message("third", 3, MessageRole.Assistant, "Third", "c"),
        )
        val result = items.toStudioMessages(now, isRunning = true)
        assertEquals(listOf("first", "second", "prompt", "third"), result.map { it.id })
        assertFalse(assertIs<StudioMessage.Reply>(result[0]).isStreaming)
        assertFalse(assertIs<StudioMessage.Reply>(result[1]).isStreaming)
        assertTrue(assertIs<StudioMessage.Reply>(result[3]).isStreaming)
    }

    @Test
    fun `user visible engine notices retain content without fake timestamps or streaming`() {
        val notice = SessionItem.Notice(info("notice", 0), "The request was interrupted")
        val result = listOf(notice).toStudioMessages(now, isRunning = true)
        val reply = assertIs<StudioMessage.Reply>(result.single())
        assertEquals("The request was interrupted", reply.text)
        assertFalse(reply.isTimestampKnown)
        assertFalse(reply.isStreaming)
    }

    @Test
    fun `cancelled invocation remains cancelled when its partial result arrives`() {
        val call = ToolCallId("command")
        val result = listOf(
            SessionItem.ToolCall(info("call", 0), call, "Command", "test", ToolCallStatus.Cancelled),
            SessionItem.ToolResult(info("output", 1), call, listOf(ContentPart.Text("partial"))),
        ).toStudioMessages(now, isRunning = false)
        assertEquals(ToolRunStatus.Cancelled, assertIs<StudioMessage.Reply>(result.single()).tools.single().status)
    }

    @Test
    fun `service notice does not detach a result from the invocation it finishes`() {
        val call = ToolCallId("read")
        val result = listOf(
            SessionItem.ToolCall(info("call", 0, "turn"), call, "Read source", "file.kt", ToolCallStatus.Running),
            SessionItem.Notice(info("notice", 1), "Context was compacted"),
            SessionItem.ToolResult(info("output", 2, "turn"), call, listOf(ContentPart.Text("source"))),
        ).toStudioMessages(now, isRunning = false)
        assertEquals(2, result.size)
        val tool = assertIs<StudioMessage.Reply>(result[0]).tools.single()
        assertEquals("Read source", tool.title)
        assertEquals(ToolRunStatus.Done, tool.status)
        assertEquals("file.kt\nsource", tool.output)
        assertEquals("Context was compacted", assertIs<StudioMessage.Reply>(result[1]).text)
    }

    @Test
    fun `the first known turn locks a preceding unlabelled answer without merging the next turn`() {
        val result = listOf(
            message("unknown", 0, MessageRole.Assistant, "Starting"),
            message("known", 1, MessageRole.Assistant, "First", "a"),
            message("next", 2, MessageRole.Assistant, "Second", "b"),
        ).toStudioMessages(now, isRunning = false)
        assertEquals(2, result.size)
        assertEquals("Starting\n\nFirst", assertIs<StudioMessage.Reply>(result[0]).text)
        assertEquals("Second", assertIs<StudioMessage.Reply>(result[1]).text)
    }

    private fun message(id: String, position: Long, role: MessageRole, text: String, turn: String? = null) =
        SessionItem.Message(info(id, position, turn), role, listOf(ContentPart.Text(text)))

    private fun info(id: String, position: Long, turn: String? = null): ItemInfo =
        ItemInfo(ItemId(id), position, revision = 0, turn = turn?.let(::TurnId))
}
