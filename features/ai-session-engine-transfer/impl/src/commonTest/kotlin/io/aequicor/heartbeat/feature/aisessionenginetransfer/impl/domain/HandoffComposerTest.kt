package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PlanStep
import io.aequicor.heartbeat.feature.aiengine.facade.api.PlanStepStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostDirective
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.message
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandoffComposerTest {
    private val request = RequestId("handoff")

    @Test
    fun `transcript keeps user and assistant turns in chronological order`() {
        val prompt = compose(
            text(0, MessageRole.User, "first question"),
            text(1, MessageRole.Assistant, "first answer"),
            text(2, MessageRole.User, "second question"),
        )
        assertEquals(request, prompt.id)
        assertTrue(prompt.indexOf("User:\nfirst question") < prompt.indexOf("Assistant:\nfirst answer"))
        assertTrue(prompt.indexOf("Assistant:\nfirst answer") < prompt.indexOf("User:\nsecond question"))
        assertTrue(prompt.text.startsWith("You are continuing a conversation"))
        assertFalse("omitted" in prompt.text)
        assertFalse("incomplete" in prompt.text)
    }

    @Test
    fun `host directives in user turns are not replayed to the next engine`() {
        val prompt = compose(
            text(0, MessageRole.User, withHostDirectives("/remember Use UTF-8", listOf("Call remember now"))),
            text(1, MessageRole.User, hostDirective("Only a hint")),
        )
        assertTrue("User:\n/remember Use UTF-8" in prompt.text)
        assertFalse("Call remember now" in prompt.text)
        assertFalse("Only a hint" in prompt.text)
        assertFalse("heartbeat-directive" in prompt.text)
    }

    @Test
    fun `a directive followed by an attachment is not replayed either`() {
        val hint = withHostDirectives("Look at this", listOf("Remember the workaround"))
        val image = ContentPart.Image(ResourceRef("r", "image/png"))
        val prompt = compose(message(0, MessageRole.User, ContentPart.Text(hint), image))
        assertTrue("User:\nLook at this\n[image omitted]" in prompt.text)
        assertFalse("Remember the workaround" in prompt.text)
    }

    @Test
    fun `private engine details are not carried over`() {
        val info = { position: Long -> ItemInfo(ItemId("i$position"), position, 0) }
        val prompt = compose(
            message(0, MessageRole.User, ContentPart.Text("visible"), ContentPart.Reasoning("hidden thoughts")),
            text(1, MessageRole.System, "system prompt"),
            SessionItem.Notice(info(2), "context compacted"),
            SessionItem.UnsupportedItem(info(3), "native-kind"),
            SessionItem.ToolCall(info(4), ToolCallId("c"), "shell", "rm secret.txt", ToolCallStatus.Succeeded),
        )
        assertTrue("visible" in prompt.text)
        listOf("hidden thoughts", "system prompt", "context compacted", "native-kind", "secret.txt").forEach {
            assertFalse(it in prompt.text, it)
        }
        assertTrue("Tool call: shell (succeeded)" in prompt.text)
    }

    @Test
    fun `engine local resources become placeholders`() {
        val image = ContentPart.Image(ResourceRef("img-1", "image/png"))
        val file = ContentPart.Resource(ResourceRef("file-1", "text/plain"))
        val prompt = compose(message(0, MessageRole.User, ContentPart.Text("look"), image, file))
        assertTrue("[image omitted]" in prompt.text)
        assertTrue("[attachment omitted]" in prompt.text)
        assertFalse("img-1" in prompt.text)
    }

    @Test
    fun `tool results and plans are summarized`() {
        val info = { position: Long -> ItemInfo(ItemId("i$position"), position, 0) }
        val prompt = compose(
            SessionItem.ToolResult(info(0), ToolCallId("c"), listOf(ContentPart.Text("x".repeat(5_000)))),
            SessionItem.ToolResult(info(1), ToolCallId("d"), emptyList(), EngineFailure.Unknown()),
            SessionItem.Plan(info(2), listOf(PlanStep("write tests", PlanStepStatus.Running))),
        )
        assertTrue("x".repeat(2_000) + "…" in prompt.text)
        assertFalse("x".repeat(2_001) in prompt.text)
        assertTrue("Tool result (failed):" in prompt.text)
        assertTrue("Plan:\n- [running] write tests" in prompt.text)
    }

    @Test
    fun `newest turns win the budget and omissions are stated`() {
        val items = (0L until 10L).map { text(it, MessageRole.User, "message-$it ".repeat(10)) }
        val prompt = composeHandoff(Transcript(items, HistoryCoverage.Complete, isTruncated = false), request, 400)
        assertTrue("message-9" in prompt.text)
        assertFalse("message-0" in prompt.text)
        assertTrue("omitted" in prompt.text)
    }

    @Test
    fun `an oversized newest turn is shortened rather than dropped`() {
        val items = listOf(text(0, MessageRole.User, "old"), text(1, MessageRole.User, "y".repeat(1_000)))
        val prompt = composeHandoff(Transcript(items, HistoryCoverage.Complete, isTruncated = false), request, 100)
        assertTrue("User:\n" + "y".repeat(94) + "…" in prompt.text)
        assertFalse("no messages" in prompt.text)
        assertTrue("omitted" in prompt.text)
    }

    @Test
    fun `truncated or partial history is disclosed`() {
        val items = listOf(text(0, MessageRole.User, "hello"))
        val truncated = composeHandoff(Transcript(items, HistoryCoverage.Complete, isTruncated = true), request)
        assertTrue("omitted" in truncated.text)
        val partial = composeHandoff(Transcript(items, HistoryCoverage.Partial, isTruncated = false), request)
        assertTrue("incomplete" in partial.text)
    }

    @Test
    fun `empty history still produces a valid handoff`() {
        val prompt = compose()
        assertTrue("no messages" in prompt.text)
        assertEquals(0, text(0, MessageRole.System, "ignored").approximateLength())
    }

    private fun compose(vararg items: SessionItem) =
        composeHandoff(Transcript(items.toList(), HistoryCoverage.Complete, isTruncated = false), request)

    private val PromptRequest.text: String
        get() = (parts.single() as ContentPart.Text).text

    private fun PromptRequest.indexOf(value: String) = text.indexOf(value).also { assertTrue(it >= 0, value) }
}
