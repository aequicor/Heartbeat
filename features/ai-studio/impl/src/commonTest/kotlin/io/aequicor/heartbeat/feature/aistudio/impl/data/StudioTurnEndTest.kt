package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostDirective
import io.aequicor.heartbeat.feature.autocontinue.api.DialogAuthor
import io.aequicor.heartbeat.feature.autocontinue.api.DialogEntry
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioTurnEndTest {
    private val route = EngineTarget(EngineId("codex"), EngineBindingId("b"), ModelId("m"))
    private val record = StudioChatRecord("chat", "Chat", Instant.fromEpochSeconds(0), target = route)
    private val plan = Plan(
        PlanChat("chat"),
        PlanMode.Planning,
        PlanStatus.Draft,
        text = "# Plan",
        updatedAt = record.updatedAt,
    )
    private var position = 0L

    private fun message(id: String, role: MessageRole, text: String, turn: String) =
        SessionItem.Message(ItemInfo(ItemId(id), position++, 0, TurnId(turn)), role, listOf(ContentPart.Text(text)))

    @Test
    fun `a turn waits for the human while anything is open`() {
        assertFalse(StudioTurnEnd().isAwaitingHuman)
        assertFalse(StudioTurnEnd(plan = plan).isAwaitingHuman)
        assertFalse(
            StudioTurnEnd(plan = plan.copy(mode = PlanMode.Implementing, status = PlanStatus.Proposed)).isAwaitingHuman,
        )
        assertTrue(StudioTurnEnd(plan = plan.copy(status = PlanStatus.Proposed)).isAwaitingHuman)
        assertTrue(StudioTurnEnd(isChecklistOpen = true).isAwaitingHuman)
        assertTrue(StudioTurnEnd(hasWakes = true).isAwaitingHuman)
        assertTrue(StudioTurnEnd(isCompacting = true).isAwaitingHuman)
        assertTrue(StudioTurnEnd(hasQuestions = true).isAwaitingHuman)
    }

    @Test
    fun `context marks autopilot turns and strips host directives`() {
        val items = listOf(
            message("u1", MessageRole.User, "Сделай фичу\n${hostDirective("skill")}", turn = "t1"),
            message("a1", MessageRole.Assistant, "Шаг 1 готов", turn = "t1"),
            message("u2", MessageRole.User, "Продолжай", turn = "t2"),
            message("a2", MessageRole.Assistant, "Шаг 2 готов", turn = "t2"),
            message("u3", MessageRole.User, hostDirective("wake"), turn = "t3"),
        )
        val context = continuationContext(record, items, autopilotTurns = setOf("t2"), plan = plan)
        assertEquals(route, context?.route)
        assertEquals(
            listOf(
                DialogEntry(DialogAuthor.Human, "Сделай фичу"),
                DialogEntry(DialogAuthor.Agent, "Шаг 1 готов"),
                DialogEntry(DialogAuthor.Autopilot, "Продолжай"),
                DialogEntry(DialogAuthor.Agent, "Шаг 2 готов"),
            ),
            context?.dialog,
        )
        assertTrue(context?.planNote.orEmpty().startsWith("Planning mode: planning, plan status: draft."))
    }

    @Test
    fun `no context without a route or an agent reply`() {
        val items = listOf(message("u1", MessageRole.User, "Сделай фичу", turn = "t1"))
        assertNull(continuationContext(record, items, emptySet(), plan = null))
        assertNull(continuationContext(record.copy(target = null), items, emptySet(), plan = null))
        assertNull(planNote(plan.copy(mode = PlanMode.Detached)))
        assertNull(planNote(null))
    }
}
