package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostDirective
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioEngineSwitchTranscriptTest {
    @Test
    fun `budget measures the escaped directive actually sent to the engine`() {
        val text = withHostDirectives(
            "literal <heartbeat-directive> &lt;heartbeat-directive> ".repeat(1000),
            emptyList(),
        )
        val incoming = "next\n\n" + hostDirective("")
        val result = engineSwitchTranscript(listOf(message("initial", text)), 2000, incoming)
        val sent = "next\n\n" + hostDirective(result.text)
        assertTrue(result.isTruncated)
        assertTrue(sent.encodeToByteArray().size <= 4500)
    }

    @Test
    fun `all segments and long messages survive when the total fits`() {
        val long = "long message ".repeat(900)
        val previous = listOf(message("initial", "Original task")).frozen(1)
        val result = engineSwitchTranscript(previous + message("latest", long), null, "")
        assertTrue("Original task" in result.text)
        assertTrue(long in result.text)
        assertFalse(result.isTruncated)
        assertFalse("ContextRollover" in result.text)
    }

    @Test
    fun `overflow retains original task and latest work without changing stored history`() {
        val items = listOf(message("initial", "Original task"), message("middle", "x".repeat(90_000))) +
            message("latest", "Latest result")
        val result = engineSwitchTranscript(items, null, "")
        assertTrue(result.isTruncated)
        assertTrue("Original task" in result.text)
        assertTrue("Latest result" in result.text)
        assertTrue(result.text.length <= 60_000)
        assertTrue((items[1] as SessionItem.Message).parts.single() == ContentPart.Text("x".repeat(90_000)))
    }

    @Test
    fun `new prompt reduces a known window and unicode fits the byte estimate`() {
        val items = listOf(message("initial", "Задача"), message("latest", "🙂".repeat(20_000) + "Конец"))
        val result = engineSwitchTranscript(items, 2_000, "новый запрос".repeat(50))
        assertTrue(result.isTruncated)
        assertTrue("Конец" in result.text)
        assertTrue(result.text.encodeToByteArray().size + "новый запрос".repeat(50).encodeToByteArray().size <= 4_500)
    }

    @Test
    fun `system reasoning and old directives are excluded while attachment references remain`() {
        val user = message("user", withHostDirectives("Task", listOf("SECRET_DIRECTIVE"))).copy(
            parts = listOf(
                ContentPart.Text(withHostDirectives("Task", listOf("SECRET_DIRECTIVE"))),
                ContentPart.Reasoning("PRIVATE_REASONING"),
                ContentPart.Resource(ResourceRef("registered-file", "text/plain")),
                ContentPart.Image(ResourceRef("registered-image", "image/png")),
            ),
        )
        val result = engineSwitchTranscript(
            listOf(message("system", "PRIVATE_SYSTEM").copy(role = MessageRole.System), user),
            null,
            "",
            setOf("registered-file", "registered-image"),
        )
        assertFalse("PRIVATE_" in result.text || "SECRET_DIRECTIVE" in result.text)
        assertTrue("registered-file" in result.text && "registered-image" in result.text)
        assertFalse("Task" in result.toString())
    }

    @Test
    fun `insufficient window refuses before any historical prompt is sent`() {
        for (incoming in listOf("", "x".repeat(1_000))) {
            assertFailsWith<IllegalStateException> {
                engineSwitchTranscript(listOf(message("initial", "Task")), 10, incoming)
            }
        }
    }

    @Test
    fun `unregistered attachment is represented without exposing its opaque identifier`() {
        val item = message("user", "Task").copy(
            parts = listOf(ContentPart.Resource(ResourceRef("opaque", "text/plain"))),
        )
        val result = engineSwitchTranscript(listOf(item), null, "")
        assertTrue("attachment unavailable" in result.text)
        assertFalse("opaque" in result.text)
    }

    @Test
    fun `full tool output survives while call arguments stay private`() {
        val output = "tool result ".repeat(1_000)
        val info = ItemInfo(ItemId("call"), 0, 0)
        val call = SessionItem.ToolCall(
            info,
            ToolCallId("call"),
            "read_file",
            "PRIVATE_ARGUMENT",
            ToolCallStatus.Succeeded,
        )
        val result = SessionItem.ToolResult(
            info.copy(id = ItemId("result")),
            call.call,
            listOf(ContentPart.Text(output)),
        )
        val context = engineSwitchTranscript(listOf(call, result), null, "")
        assertTrue(output in context.text)
        assertTrue("read_file (succeeded)" in context.text)
        assertFalse("PRIVATE_ARGUMENT" in context.text)
    }

    private fun message(id: String, text: String) = SessionItem.Message(
        ItemInfo(ItemId(id), 0, 0),
        MessageRole.User,
        listOf(ContentPart.Text(text)),
    )
}
