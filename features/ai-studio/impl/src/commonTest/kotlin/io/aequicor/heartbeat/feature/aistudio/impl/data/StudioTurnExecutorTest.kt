package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRun
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.reflect.safeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudioTurnExecutorTest {
    @Test
    fun `ordinary project accepted failure retains ownership until native cancellation and tool cleanup`() = runTest {
        val fixture = TurnFixture()
        fixture.host.isIsolated = false
        fixture.host.stopCheckFailure = IllegalStateException("StopCheckFailed")
        fixture.active.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
        val result = async { fixture.executor.execute(fixture.host, fixture.request) }
        runCurrent()
        assertEquals(listOf("open", "submit", "cancel"), fixture.events)
        assertFalse(result.isCompleted)
        assertTrue(fixture.machine.sent.isEmpty())
        assertEquals(null, fixture.tools.finished)

        fixture.active.state.value = ActiveSessionState.Ready(fixture.turn.copy(outcome = TurnOutcome.Cancelled))
        runCurrent()
        assertEquals("finish-start", fixture.events.last())
        assertFalse(result.isCompleted)
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Failed, result.await())
        assertEquals(listOf("finish-end", "failure"), fixture.events.takeLast(2))
        assertEquals(fixture.active.ref to fixture.turn.id, fixture.tools.finished)
        assertTrue(fixture.machine.sent.isEmpty())
    }

    @Test
    fun `terminal turn waits for tool cleanup before persisting settlement or releasing runtime`() = runTest {
        val fixture = TurnFixture()
        val result = async { fixture.executor.execute(fixture.host, fixture.request) }
        runCurrent()
        assertEquals(listOf("started", "open", "submit", "accepted", "finish-start"), fixture.events)
        assertFalse(result.isCompleted)
        assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunSettled })

        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Completed, result.await())
        assertEquals(listOf("finish-end", "refresh", "settled", "outcome"), fixture.events.takeLast(4))
        assertEquals(fixture.active.ref to fixture.turn.id, fixture.tools.finished)
    }

    @Test
    fun `opening a native session failure rejects the persisted request without inventing a turn`() = runTest {
        val fixture = TurnFixture()
        fixture.host.openFailure = IllegalStateException("CannotOpenNativeSession")
        assertEquals(RunOutcome.Failed, fixture.executor.execute(fixture.host, fixture.request))
        assertEquals(listOf("started", "open", "rejected", "failure"), fixture.events)
        assertEquals(
            WorktreeIntent.Public.RunRejected("chat", fixture.request.request, "RequestRejected"),
            fixture.machine.sent.last(),
        )
        assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunAccepted })
    }

    @Test
    fun `failed durable preparation never opens or submits a native session`() = runTest {
        val fixture = TurnFixture()
        fixture.machine.isPrepared = false
        assertEquals(RunOutcome.Failed, fixture.executor.execute(fixture.host, fixture.request))
        assertEquals(listOf("started", "rejected", "failure"), fixture.events)
        assertEquals(0, fixture.host.openCount)
        assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunAccepted })
    }
}

private class TurnFixture {
    val events = mutableListOf<String>()
    val request = StudioTurnRequest(
        "chat",
        "Implement the task",
        ExecutorSettings,
        WorktreeRunKind.Coding,
        RequestId("request"),
    )
    val turn = Turn(
        TurnId("turn"),
        request.request,
        EngineTarget(
            EngineId("test"),
            EngineBindingId("binding"),
            ModelId("model"),
        ),
        TurnOutcome.Completed,
    )
    val active = ExecutorSession(turn)
    val tools = ExecutorTools(events)
    val machine = ExecutorMachine(events)
    val host = ExecutorHost(active, turn, events)
    val executor = StudioTurnExecutor(StudioWorktrees(executorRegistry(machine), tools), tools)
}

private class ExecutorTools(private val events: MutableList<String>) : ProfileAgentTools by NoAgentTools {
    val release = CompletableDeferred<Unit>()
    var finished: Pair<SessionRef, TurnId>? = null
    override suspend fun finishTurn(session: SessionRef, turn: TurnId) {
        events += "finish-start"
        release.await()
        finished = session to turn
        events += "finish-end"
    }
}

private class ExecutorHost(
    private val active: ExecutorSession,
    private val turn: Turn,
    private val events: MutableList<String>,
) : StudioTurnHost {
    var openFailure: Exception? = null
    var stopCheckFailure: Exception? = null
    var isIsolated = true
    var openCount = 0
    override suspend fun isWorktree(id: String) = isIsolated
    override suspend fun openTurn(id: String, settings: RunSettings): ActiveSession {
        openCount++
        events += "open"
        openFailure?.let { throw it }
        return active
    }
    override suspend fun submitTurn(active: ActiveSession, request: StudioTurnRequest): TurnId {
        events += "submit"
        return turn.id
    }
    override suspend fun shouldStop(id: String): Boolean {
        stopCheckFailure?.let { throw it }
        return false
    }
    override suspend fun requestStop(id: String, active: ActiveSession, turn: TurnId) {
        events += "cancel"
    }
    override suspend fun observeHistory(id: String, history: SessionHistory): Unit = awaitCancellation()
    override suspend fun refreshHistory(id: String, history: SessionHistory) {
        events += "refresh"
    }
    override suspend fun updatePermissions(id: String, state: ActiveSessionState) = Unit
    override suspend fun outcome(id: String, outcome: TurnOutcome?): RunOutcome {
        events += "outcome"
        return RunOutcome.Completed
    }
    override suspend fun failedTurn(id: String, error: Exception): RunOutcome {
        events += "failure"
        return RunOutcome.Failed
    }
}

private class ExecutorSession(turn: Turn) : ActiveSession {
    override val ref = SessionRef(EngineId("test"), SessionSourceId("local"), "session")
    override val route = ExecutionRoute(
        ref.engine,
        EngineBindingId("binding"),
        AuthSourceId("auth"),
        AuthRevision.Known("revision"),
        WorkspaceRef("isolated"),
    )
    override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready(turn))
    private val history = object : SessionHistory {
        override suspend fun page(request: HistoryPageRequest) = error("Unused")
        override fun watch(after: HistoryCheckpoint) = emptyFlow<SessionEvent>()
    }
    override val features = object : EngineFeatures {
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
            key.type.safeCast(history)?.let { FeatureAccess.Available(it) } ?: FeatureAccess.Unsupported
    }
    override suspend fun close() = Unit
}

private class ExecutorMachine(private val events: MutableList<String>) :
    MachineRef<WorktreeState, WorktreeIntent.Public, WorktreeOutput> {
    override val name = WorktreeMachineKey.name
    override val state = MutableStateFlow<WorktreeState>(
        WorktreeState.Ready(
            mapOf(
                "chat" to WorktreeTask(
                    "chat",
                    WorkspaceRef("original"),
                    WorkspaceRef("isolated"),
                    phase = WorktreePhase.Idle,
                ),
            ),
        ),
    )
    override val outputs = emptyFlow<WorktreeOutput>()
    val sent = mutableListOf<WorktreeIntent.Public>()
    var isPrepared = true
    override suspend fun send(intent: WorktreeIntent.Public): SendResult {
        sent += intent
        when (intent) {
            is WorktreeIntent.Public.RunStarted -> {
                events += "started"
                val task = (state.value as WorktreeState.Ready).tasks.getValue("chat")
                state.value = WorktreeState.Ready(
                    mapOf(
                        "chat" to task.copy(
                            phase = if (isPrepared) WorktreePhase.Working else WorktreePhase.Failed,
                            run = WorktreeRun(intent.request, intent.kind, isPrepared = isPrepared),
                        ),
                    ),
                )
            }

            is WorktreeIntent.Public.RunAccepted -> events += "accepted"

            is WorktreeIntent.Public.RunSettled -> events += "settled"

            is WorktreeIntent.Public.RunRejected -> events += "rejected"

            WorktreeIntent.Public.Start, WorktreeIntent.Public.RetryLoad,
            is WorktreeIntent.Public.Prepare, is WorktreeIntent.Public.TrackMainSession,
            is WorktreeIntent.Public.RunObservationLost, is WorktreeIntent.Public.TaskCompleteSignaled,
            is WorktreeIntent.Public.ChooseAction, is WorktreeIntent.Public.ActionDelivered,
            is WorktreeIntent.Public.ActionDeliveryFailed, is WorktreeIntent.Public.Recheck,
            is WorktreeIntent.Public.ProposeBuildPlan, is WorktreeIntent.Public.ApproveBuildPlan,
            is WorktreeIntent.Public.RunBuild, is WorktreeIntent.Public.CancelBuild,
            -> error(
                "Unexpected intent $intent",
            )
        }
        return SendResult.Accepted
    }
}

private fun executorRegistry(machine: ExecutorMachine) = object : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // This fixture serves only the checked worktree key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O> {
        check(key == WorktreeMachineKey)
        return machine as MachineRef<S, P, O>
    }
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))
    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key).send(intent)
}

private val ExecutorSettings = RunSettings("model", ReasoningEffort.Medium, ApprovalMode.Ask)
