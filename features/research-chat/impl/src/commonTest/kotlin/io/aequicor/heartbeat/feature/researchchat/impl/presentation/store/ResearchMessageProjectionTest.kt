package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResearchMessageProjectionTest {
    private val call = ToolCallId("search")

    @Test
    fun `reasoning prose and search results stay in native order inside one streaming answer`() {
        val items = listOf(
            message("intro", 0, ContentPart.Text("Before"), ContentPart.Reasoning("Compare sources")),
            SessionItem.ToolCall(info("call", 1), call, "web_search", "query", ToolCallStatus.Running),
            SessionItem.ToolResult(info("result", 2), call, listOf(ContentPart.Text("Evidence"))),
            message("final", 3, ContentPart.Text("After")),
        )
        val answer = items.toResearchMessages(isRunning = true).single()
        assertTrue(answer.isStreaming)
        assertEquals("Before\n\nAfter", answer.text)
        assertEquals("Before", assertIs<ResearchPartUi.Text>(answer.parts[0]).text)
        assertEquals("Compare sources", assertIs<ResearchPartUi.Reasoning>(answer.parts[1]).text)
        val tool = assertIs<ResearchPartUi.Tool>(answer.parts[2])
        assertEquals(ResearchToolStatus.Complete, tool.status)
        assertEquals("query\nEvidence", tool.output)
        assertEquals("After", assertIs<ResearchPartUi.Text>(answer.parts[3]).text)
    }

    @Test
    fun `native positions restarting in each followup do not reorder stored conversation`() {
        val items = listOf(
            user("user1", "First", "turn1"),
            message("answer1", 1, ContentPart.Text("Answer one"), turn = "turn1"),
            user("user2", "Second", "turn2"),
            message("answer2", 1, ContentPart.Text("Answer two"), turn = "turn2"),
        )
        val messages = items.toResearchMessages(isRunning = true)
        assertEquals(listOf("user1", "answer1", "user2", "answer2"), messages.map { it.id })
        assertEquals(listOf(false, false, false, true), messages.map { it.isStreaming })
    }

    @Test
    fun `late tool result updates original invocation across notice without a duplicate tool`() {
        val items = listOf(
            SessionItem.ToolCall(info("call", 0), call, "web_fetch", "", ToolCallStatus.Running),
            SessionItem.Notice(info("notice", 1), "Connection restored"),
            SessionItem.ToolResult(info("result", 2), call, listOf(ContentPart.Text("Source body"))),
        )
        val messages = items.toResearchMessages(isRunning = false)
        assertEquals(2, messages.size)
        val tool = assertIs<ResearchPartUi.Tool>(messages.first().parts.single())
        assertEquals(ResearchToolStatus.Complete, tool.status)
        assertEquals("Source body", tool.output)
        assertTrue(messages.last().isNotice)
    }

    @Test
    fun `cancelled and failed tools never become successful merely because output arrived`() {
        for (status in listOf(ToolCallStatus.Cancelled, ToolCallStatus.Failed)) {
            val messages = listOf(
                SessionItem.ToolCall(info("call", 0), call, "web_search", "", status),
                SessionItem.ToolResult(info("result", 1), call, listOf(ContentPart.Text("Partial"))),
            ).toResearchMessages(isRunning = false)
            val expected = if (status == ToolCallStatus.Cancelled) {
                ResearchToolStatus.Cancelled
            } else {
                ResearchToolStatus.Failed
            }
            assertEquals(expected, assertIs<ResearchPartUi.Tool>(messages.single().parts.single()).status)
        }
        val failed = listOf(
            SessionItem.ToolCall(info("call", 0), call, "web_search", "", ToolCallStatus.Running),
            SessionItem.ToolResult(
                info("result", 1),
                call,
                emptyList(),
                EngineFailure.Transport(TransportFailureReason.NetworkUnavailable),
            ),
        ).toResearchMessages(isRunning = false)
        assertEquals(ResearchToolStatus.Failed, assertIs<ResearchPartUi.Tool>(failed.single().parts.single()).status)
    }

    @Test
    fun `resource payloads and unsupported protocol reasoning never become exposed message parts`() {
        val items = listOf(
            SessionItem.UnsupportedItem(info("hidden", 0), "reasoning"),
            message("answer", 1, ContentPart.Resource(ResourceRef("data:text/plain;base64,secret", "text/plain"))),
        )
        assertTrue(items.toResearchMessages(isRunning = false).isEmpty())
        val pending = listOf(
            SessionItem.ToolCall(info("pending", 0), call, "web_search", "", ToolCallStatus.Pending),
        ).toResearchMessages(isRunning = false).single()
        assertFalse(pending.isStreaming)
        assertEquals(ResearchToolStatus.Pending, assertIs<ResearchPartUi.Tool>(pending.parts.single()).status)
    }

    @Test
    fun `first known turn locks the answer boundary after turnless introductory content`() {
        val items = listOf(
            message("intro", 0, ContentPart.Text("Intro"), turn = null),
            message("first", 1, ContentPart.Text("First answer"), turn = "first"),
            message("second", 2, ContentPart.Text("Second answer"), turn = "second"),
        )
        val messages = items.toResearchMessages(isRunning = false)
        assertEquals(listOf("Intro\n\nFirst answer", "Second answer"), messages.map { it.text })
    }

    private fun info(id: String, position: Long, turn: String? = "turn") =
        ItemInfo(ItemId(id), position, 0, turn?.let(::TurnId))

    private fun message(id: String, position: Long, vararg parts: ContentPart, turn: String? = "turn") =
        SessionItem.Message(info(id, position, turn), MessageRole.Assistant, parts.toList())

    private fun user(id: String, text: String, turn: String) =
        SessionItem.Message(info(id, 0, turn), MessageRole.User, listOf(ContentPart.Text(text)))
}
