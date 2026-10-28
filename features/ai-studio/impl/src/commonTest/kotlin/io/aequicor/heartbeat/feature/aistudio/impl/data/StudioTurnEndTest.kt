package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostDirective
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.ChatAuto
import io.aequicor.heartbeat.feature.autocontinue.api.DialogAuthor
import io.aequicor.heartbeat.feature.autocontinue.api.DialogEntry
import io.aequicor.heartbeat.feature.autocontinue.api.Phase
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_REQUEST_PREFIX
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun request(id: String, isUserPrompt: Boolean = false, userInstruction: String? = null) = StudioTurnRequest(
        "chat",
        prompt = "text",
        settings = DefaultRunSettings,
        kind = WorktreeRunKind.Coding,
        request = RequestId(id),
        isUserPrompt = isUserPrompt,
        userInstruction = userInstruction,
    )

    @Test
    fun `turns are told apart by who started them`() {
        assertEquals(StudioTurnKind.Human, request("r", isUserPrompt = true).turnKind())
        assertEquals(StudioTurnKind.Human, request("r", userInstruction = "do it").turnKind())
        assertEquals(StudioTurnKind.Host, request("autopilot-1").turnKind())
        assertEquals(StudioTurnKind.Host, request("continue-1").turnKind())
        assertEquals(StudioTurnKind.Service, request("${HANDOFF_REQUEST_PREFIX}1").turnKind())
        assertEquals(StudioTurnKind.Service, request("r").copy(isRollover = true).turnKind())
    }

    @Test
    fun `context upkeep judges again only a chat that paused or waits on`() {
        val compaction = request("compact-1")
        val handoff = request("${HANDOFF_REQUEST_PREFIX}1")
        val paused = ChatAuto(AutoContinueMode.Autopilot, Phase.Paused)
        assertTrue(isRejudgedAfterService(compaction, paused))
        assertTrue(isRejudgedAfterService(compaction, ChatAuto(AutoContinueMode.Suggest)))
        assertFalse(isRejudgedAfterService(handoff, paused))
        assertFalse(isRejudgedAfterService(compaction, null))
        assertFalse(isRejudgedAfterService(compaction, ChatAuto(AutoContinueMode.Off)))
        assertFalse(isRejudgedAfterService(compaction, ChatAuto(AutoContinueMode.Suggest, Phase.Thinking(1))))
        assertFalse(
            isRejudgedAfterService(compaction, ChatAuto(AutoContinueMode.Autopilot, Phase.Countdown(1, "go", 5))),
        )
    }

    @Test
    fun `a turn that throws or is cancelled ends as failed`() = runTest {
        val lifecycle = RecordingLifecycle()
        assertEquals(RunOutcome.Completed, lifecycle.observe(request("a")) { RunOutcome.Completed })
        assertFailsWith<IllegalStateException> { lifecycle.observe(request("b")) { error("boom") } }
        assertFailsWith<CancellationException> {
            lifecycle.observe(request("c")) { throw CancellationException("stopped") }
        }
        assertEquals(listOf("a", "b", "c"), lifecycle.started)
        assertEquals(
            listOf("a" to RunOutcome.Completed, "b" to RunOutcome.Failed, "c" to RunOutcome.Failed),
            lifecycle.finished,
        )
    }

    @Test
    fun `a long dialog keeps the task and the newest entries`() {
        val items = listOf(message("u0", MessageRole.User, "task", turn = "t0")) +
            (1..80).flatMap { index ->
                listOf(
                    message("u$index", MessageRole.User, "next $index", turn = "t$index"),
                    message("a$index", MessageRole.Assistant, "done $index", turn = "t$index"),
                )
            }
        val dialog = continuationContext(record, items, emptySet(), plan = null)?.dialog.orEmpty()
        assertEquals(60, dialog.size)
        assertEquals(DialogEntry(DialogAuthor.Human, "task"), dialog.first())
        assertEquals(DialogEntry(DialogAuthor.Agent, "done 80"), dialog.last())
    }

    private class RecordingLifecycle : StudioTurnLifecycle {
        val started = mutableListOf<String>()
        val finished = mutableListOf<Pair<String, RunOutcome>>()

        override suspend fun started(request: StudioTurnRequest) {
            started += request.request.value
        }

        override suspend fun finished(request: StudioTurnRequest, outcome: RunOutcome) {
            finished += request.request.value to outcome
        }
    }
}
