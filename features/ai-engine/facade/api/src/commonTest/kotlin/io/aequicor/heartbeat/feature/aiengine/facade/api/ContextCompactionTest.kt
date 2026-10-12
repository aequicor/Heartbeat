package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextCompactionTest {
    @Test
    fun `essence drops reasoning, tool arguments and host directives`() {
        val items = listOf(
            message(0, MessageRole.User, ContentPart.Text("fix issue\n\n" + hostDirective("secret host note"))),
            message(1, MessageRole.Assistant, ContentPart.Reasoning("private thinking"), ContentPart.Text("Done.")),
            SessionItem.ToolCall(info(2), ToolCallId("c1"), "shell", """{"cmd":"rm -rf"}""", ToolCallStatus.Succeeded),
            SessionItem.ToolResult(info(3), ToolCallId("c1"), listOf(ContentPart.Text("ok"))),
        )

        val essence = sessionEssence(items, budgetChars = 10_000)

        assertEquals(
            "User: fix issue\n\nAssistant: Done.\n\nTool call: shell (succeeded)\n\nTool result: ok",
            essence.text,
        )
        assertFalse("thinking" in essence.text)
        assertFalse("rm -rf" in essence.text)
        assertEquals(4, essence.keptItems)
        assertEquals(0, essence.omittedItems)
    }

    @Test
    fun `essence keeps the newest entries within the budget`() {
        val items = (0 until 10).map { message(it.toLong(), MessageRole.User, ContentPart.Text("message $it")) }

        val essence = sessionEssence(items, budgetChars = 50)

        assertTrue(essence.text.endsWith("User: message 9"))
        assertFalse("message 0" in essence.text)
        assertEquals(10, essence.keptItems + essence.omittedItems)
        assertTrue(essence.text.length <= 50)
    }

    @Test
    fun `essence keeps the beginning of a single oversized entry`() {
        val items = listOf(message(0, MessageRole.Assistant, ContentPart.Text("x".repeat(500))))

        val essence = sessionEssence(items, budgetChars = 100, EssenceOptions(messageChars = 10_000))

        assertEquals(1, essence.keptItems)
        assertEquals(100, essence.text.length)
    }

    @Test
    fun `essence fits budgets shorter than the ellipsis`() {
        val items = listOf(message(0, MessageRole.Assistant, ContentPart.Text("x".repeat(500))))

        for (budget in 1..4) {
            val essence = sessionEssence(items, budgetChars = budget)

            assertEquals(1, essence.keptItems)
            assertEquals(budget, essence.text.length)
        }
    }

    @Test
    fun `long tool output keeps its beginning and end`() {
        val output = "head-" + "x".repeat(2_000) + "-tail"
        val items = listOf(SessionItem.ToolResult(info(0), ToolCallId("c1"), listOf(ContentPart.Text(output))))

        val text = sessionEssence(items, budgetChars = 10_000, EssenceOptions(toolResultChars = 60)).text

        assertTrue(text.startsWith("Tool result: head-"))
        assertTrue(text.endsWith("-tail"))
        assertTrue(text.length < 100)
    }

    @Test
    fun `tool output can be reduced to its size`() {
        val items = listOf(SessionItem.ToolResult(info(0), ToolCallId("c1"), listOf(ContentPart.Text("abc"))))

        val text = sessionEssence(items, budgetChars = 100, EssenceOptions(toolResultChars = 0)).text

        assertEquals("Tool result: 3 chars", text)
    }

    @Test
    fun `a compaction request has no parts and a prompt has parts`() {
        PromptRequest(RequestId("r"), emptyList(), compaction = ContextCompaction())
        assertFailsWith<IllegalArgumentException> { PromptRequest(RequestId("r"), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            PromptRequest(RequestId("r"), listOf(ContentPart.Text("x")), compaction = ContextCompaction())
        }
        assertFailsWith<IllegalArgumentException> { ContextCompaction(" ") }
    }

    @Test
    fun `compaction instructions never reach toString`() {
        val request = PromptRequest(RequestId("r"), emptyList(), compaction = ContextCompaction("private focus"))

        assertFalse("private" in request.toString())
        assertFalse("private" in request.compaction.toString())
    }

    @Test
    fun `stored prompts and notices without new fields still decode`() {
        val json = Json
        val promptJson = json.encodeToString(PromptRequest.serializer(), TestPrompt)
        val noticeJson = json.encodeToString(SessionItem.serializer(), SessionItem.Notice(info(0), "compacted"))

        assertFalse("compaction" in promptJson)
        assertFalse("kind" in noticeJson)
        assertNull(json.decodeFromString(PromptRequest.serializer(), promptJson).compaction)
        assertNull((json.decodeFromString(SessionItem.serializer(), noticeJson) as SessionItem.Notice).kind)
    }

    private fun info(position: Long) = ItemInfo(ItemId("i$position"), position, 0)

    private fun message(position: Long, role: MessageRole, vararg parts: ContentPart) =
        SessionItem.Message(info(position), role, parts.toList())
}
