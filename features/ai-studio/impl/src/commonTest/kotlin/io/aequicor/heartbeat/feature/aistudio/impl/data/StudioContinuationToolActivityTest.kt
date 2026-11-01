package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.autocontinue.api.AutopilotContextSettings
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationContext
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationToolActivity
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioContinuationToolActivityTest {
    private val route = EngineTarget(EngineId("deepseek-harness"), EngineBindingId("b"), ModelId("m"))
    private val record = StudioChatRecord("chat", "Chat", Instant.fromEpochSeconds(0), target = route)
    private var position = 0L

    @Test
    fun `answered MCP questionnaire retains execution proof with detailed context disabled`() {
        val context = context(listOf(call("q"), result("q"), message("report", "Selected blue. Test complete.")))
        assertEquals(
            listOf(ContinuationToolActivity("questionnaire_ask", ToolCallStatus.Succeeded)),
            context.toolActivity,
        )
        assertNull(context.extraContext)
        val serialized = Json.encodeToString(ContinuationContext.serializer(), context)
        assertFalse("PRIVATE_ARGUMENT" in serialized)
        assertFalse("PRIVATE_ANSWER" in serialized)
    }

    @Test
    fun `questionnaire and background result selection uses normalized MCP names`() {
        val items = listOf(
            call("q"),
            result("q"),
            call("bg", "mcp__heartbeat_tools__subagent_receive"),
            result("bg", "background output"),
            message("report", "Done"),
        )
        val questions = context(items, AutopilotContextSettings(questionnaires = true)).extraContext.orEmpty()
        assertTrue("Tool: questionnaire_ask" in questions)
        assertTrue("PRIVATE_ANSWER" in questions)
        assertFalse("PRIVATE_ARGUMENT" in questions)
        assertFalse("background output" in questions)
        val background = context(items, AutopilotContextSettings(backgroundResults = true)).extraContext.orEmpty()
        assertTrue("background output" in background)
        assertFalse("PRIVATE_ANSWER" in background)
        assertNull(context(items, AutopilotContextSettings(toolResults = true)).extraContext)
    }

    @Test
    fun `old successes do not prove that a later turn executed the same tool`() {
        val context = context(
            listOf(
                call("old", turn = "old"),
                result("old", turn = "old"),
                message("old-report", "Done", "old"),
                message("new-request", "Try another questionnaire", "new", MessageRole.User),
                call("new", status = ToolCallStatus.Cancelled),
                message("report", "Cancelled"),
            ),
        )
        assertEquals(
            listOf(ContinuationToolActivity("questionnaire_ask", ToolCallStatus.Cancelled)),
            context.toolActivity,
        )
        assertTrue(context(listOf(message("alone", "No observed tools"))).toolActivity.isEmpty())
    }

    @Test
    fun `a result settles an adapter call that still reports running without overriding failure or cancellation`() {
        for (initial in ToolCallStatus.entries) {
            val context = context(listOf(call("q", status = initial), result("q"), message("report", "Done")))
            val expected = when (initial) {
                ToolCallStatus.Failed, ToolCallStatus.Cancelled -> initial
                else -> ToolCallStatus.Succeeded
            }
            assertEquals(expected, context.toolActivity.single().status)
        }
    }

    @Test
    fun `tool only new turns never inherit the old questionnaires success`() {
        for (status in listOf(ToolCallStatus.Pending, ToolCallStatus.Failed, ToolCallStatus.Cancelled)) {
            val context = context(
                listOf(
                    call("old", turn = "old"),
                    result("old", turn = "old"),
                    message("old-report", "Done", "old"),
                    message("new-request", "Another questionnaire", "new", MessageRole.User),
                    call("new", status = status),
                ),
            )
            assertEquals(listOf(ContinuationToolActivity("questionnaire_ask", status)), context.toolActivity)
        }
    }

    @Test
    fun `a reused native call id cannot reuse another turns result`() {
        val context = context(
            listOf(
                call("same", turn = "old"),
                result("same", turn = "old"),
                message("old-report", "Done", "old"),
                call("same", status = ToolCallStatus.Running),
                message("report", "Working"),
            ),
        )
        assertEquals(ToolCallStatus.Running, context.toolActivity.single().status)
    }

    @Test
    fun `failed results never become proof of success even with a stale call status`() {
        val context = context(
            listOf(call("q"), result("q").copy(failure = EngineFailure.Unknown()), message("report", "Failed")),
        )
        assertEquals(ToolCallStatus.Failed, context.toolActivity.single().status)
    }

    private fun context(items: List<SessionItem>, settings: AutopilotContextSettings = AutopilotContextSettings()) =
        requireNotNull(continuationContext(record, items, emptySet(), null, settings))

    private fun info(id: String, turn: String) = ItemInfo(ItemId(id), position++, 0, TurnId(turn))

    private fun call(
        id: String,
        name: String = "mcp__heartbeat_tools__questionnaire_ask",
        turn: String = "new",
        status: ToolCallStatus = ToolCallStatus.Succeeded,
    ) = SessionItem.ToolCall(info("call-$id", turn), ToolCallId(id), name, "PRIVATE_ARGUMENT", status)

    private fun result(id: String, text: String = "PRIVATE_ANSWER", turn: String = "new") = SessionItem.ToolResult(
        info("result-$id", turn),
        ToolCallId(id),
        listOf(ContentPart.Text(text)),
    )

    private fun message(id: String, text: String, turn: String = "new", role: MessageRole = MessageRole.Assistant) =
        SessionItem.Message(info(id, turn), role, listOf(ContentPart.Text(text)))
}
