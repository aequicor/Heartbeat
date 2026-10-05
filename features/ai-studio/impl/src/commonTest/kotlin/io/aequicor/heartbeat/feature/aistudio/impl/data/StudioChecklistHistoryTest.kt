package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.checklist.api.ChecklistCompletionMode
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEvent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class StudioChecklistHistoryTest {
    private val now = Instant.fromEpochSeconds(100)
    private val call = ToolCallId("call_0")

    @Test
    fun `restored native history keeps cards on their answers without turns or bridge call ids`() {
        // Pi restores complete native history with fresh item ids, null turns and reusable native tool ids.
        val items = listOf(
            prompt("first-prompt", 0),
            invocation("first-call", 1),
            result("first-result", 2, created("first-card")),
            prompt("second-prompt", 3),
            invocation("second-call", 4),
            result("second-result", 5, created("second-card")),
        )
        val replies = items.toStudioMessages(now, false)
            .withChecklists(listOf(event("first-card"), event("second-card")))
            .filterIsInstance<StudioMessage.Reply>()
        assertEquals(listOf("first-call", "second-call"), replies.map { it.id })
        assertEquals(listOf(listOf("first-card"), listOf("second-card")), replies.map { it.checklistIds })
        assertEquals(listOf("first-card", "second-card"), replies.map { it.tools.single().createdChecklistId })
    }

    @Test
    fun `arguments and unrelated tool results cannot manufacture a checklist anchor`() {
        val declaration = invocation("call", 0).copy(arguments = created("forged-card"))
        assertNull(listOf(declaration).projectedChecklistId())
        assertNull(listOf(declaration, result("result", 1, "Invalid checklist specification")).projectedChecklistId())
        assertNull(
            listOf(declaration.copy(name = "read_file"), result("result", 1, created("forged-card")))
                .projectedChecklistId(),
        )
        assertNull(
            listOf(declaration, result("result", 1, created("forged-card") + " extra text"))
                .projectedChecklistId(),
        )
        assertNull(
            listOf(declaration, result("result", 1, created("forged-card")).copy(failure = EngineFailure.Unknown()))
                .projectedChecklistId(),
        )
    }

    @Test
    fun `hosted MCP creation results provide the same stable anchor`() {
        val declaration = invocation("call", 0).copy(name = "mcp__heartbeat_tools__checklist_create")
        assertEquals(
            "saved-card",
            listOf(declaration, result("result", 1, created("saved-card"))).projectedChecklistId(),
        )
    }

    private fun List<SessionItem>.projectedChecklistId(): String? = toStudioMessages(now, false)
        .filterIsInstance<StudioMessage.Reply>().single().tools.single().createdChecklistId

    private fun prompt(id: String, position: Long) = SessionItem.Message(
        info(id, position),
        MessageRole.User,
        listOf(ContentPart.Text("Please continue")),
    )

    private fun invocation(id: String, position: Long) = SessionItem.ToolCall(
        info(id, position),
        call,
        "checklist_create",
        "{}",
        ToolCallStatus.Succeeded,
    )

    private fun result(id: String, position: Long, text: String) =
        SessionItem.ToolResult(info(id, position), call, listOf(ContentPart.Text(text)))

    private fun info(id: String, position: Long) = ItemInfo(ItemId(id), position, revision = 0, turn = null)

    private fun created(id: String) = "Checklist $id created. End your turn and wait for the user."

    private fun event(id: String) = ChecklistEvent(
        id = id,
        revision = 1,
        session = SessionRef(EngineId("pi"), SessionSourceId("local"), "session"),
        request = RequestId("request-$id"),
        turn = "original-turn-$id",
        callId = "bridge-generated-$id",
        mode = ChecklistCompletionMode.MarkSessionReady,
        status = ChecklistStatus.Open,
    )
}
