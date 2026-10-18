package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperProgress
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRun
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class EngineStudioHelperRunsTest {
    private val helper = HelperId("helper")

    @Test
    fun `handed off continuation exposes its completed result and blocks pending continuation`() = runTest {
        val fixture = NativeHelperFixture()
        val original = RequestId("original")
        val continued = RequestId("continued")
        val task = WorktreeTask(
            helper.value,
            WorkspaceRef("project"),
            run = WorktreeRun(continued, outcome = TurnOutcome.Completed),
            virtualSandbox = VirtualSandboxState(
                phase = VirtualSandboxPhase.HandedOff,
                baseCommit = "base",
                snapshot = "snapshot",
                resultCommit = "snapshot",
            ),
            virtualInterruptedRequests = listOf(original),
            virtualContinuations = mapOf(original.value to continued.value),
        )
        val machine = HelperResultMachine(task)
        val runs = helperRuns(
            this,
            fixture,
            null,
            worktrees = StudioWorktrees(helperResultRegistry(machine), NoAgentTools),
        )
        assertEquals(continued, runs.virtualResultRequest(helper, original))
        machine.state.value = WorktreeState.Ready(mapOf(helper.value to task.copy(pendingVirtualContinuation = true)))
        assertNull(runs.virtualResultRequest(helper, original))
        machine.state.value = WorktreeState.Ready(
            mapOf(helper.value to task.copy(run = task.run?.copy(outcome = null))),
        )
        assertNull(runs.virtualResultRequest(helper, original))
    }

    @Test
    fun `progress reports only the live turn of the exact request with its pending permissions`() = runTest {
        val fixture = NativeHelperFixture()
        val runs = helperRuns(this, fixture, live = fixture)
        val request = fixture.turn.request!!
        val running = fixture.turn.copy(outcome = null)
        fixture.state.value = ActiveSessionState.Running(running)
        assertEquals(HelperProgress(request, fixture.ref, running.id), runs.helperProgress(helper, request))
        assertNull(runs.helperProgress(helper, RequestId("foreign")))
        val permission = PermissionRequest(
            PermissionRequestId("permission"),
            running.id,
            "Run the command?",
            listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
        )
        fixture.state.value = ActiveSessionState.AwaitingUserAction(running, listOf(permission))
        assertEquals(listOf(permission), runs.helperProgress(helper, request)?.permissions)
        assertNull(runs.helperProgress(helper, RequestId("foreign")))
        fixture.state.value = ActiveSessionState.Ready(fixture.turn.copy(request = RequestId("foreign")))
        assertNull(runs.helperProgress(helper, request))
    }

    @Test
    fun `progress without a live helper handle is no observation`() = runTest {
        val fixture = NativeHelperFixture()
        fixture.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
        assertNull(helperRuns(this, fixture, live = null).helperProgress(helper, fixture.turn.request!!))
    }

    @Test
    fun `host directives of a helper prompt reach the engine-only turn directives`() = runTest {
        val fixture = NativeHelperFixture()
        val host = HelperRunsHost()
        val runs = helperRuns(this, fixture, live = null, host = host)
        val prompt = HelperPrompt(RequestId("action_a1"), "Visible task", directives = listOf("Return a summary"))
        fixture.attempts.prepare(helper, prompt)
        runs.runHelper(
            helper,
            prompt,
            StudioHelperSubmission(fixture.attempts, emptyHelperAdmissions(), helper, prompt.request),
        ) { _, _ -> }
        val executed = host.executed.single()
        assertEquals("Visible task", executed.prompt)
        assertEquals(listOf("Return a summary"), executed.directives)
    }

    private fun helperRuns(
        scope: CoroutineScope,
        fixture: NativeHelperFixture,
        live: ActiveSession?,
        host: StudioRunHost = HelperRunsHost(),
        worktrees: StudioWorktrees? = null,
    ) = EngineStudioHelperRuns(
        lazyOf(
            object : StudioHelperAccess {
                override suspend fun helperRecord(helper: HelperId) = fixture.record
                override suspend fun openHelper(record: StudioChatRecord): ActiveSession = error("Must not open")
                override suspend fun liveHelper(helper: HelperId): ActiveSession? = live
            },
        ),
        lazyOf(HelperRunsRuntime()),
        lazyOf(host),
        StudioRunCoordinator(RunProfile(scope), RunClock),
        fixture.native,
        worktrees,
    )
}

private class HelperResultMachine(task: WorktreeTask) :
    MachineRef<WorktreeState, WorktreeIntent.Public, WorktreeOutput> {
    override val name = WorktreeMachineKey.name
    override val state = MutableStateFlow<WorktreeState>(WorktreeState.Ready(mapOf(task.chatId to task)))
    override val outputs = emptyFlow<WorktreeOutput>()
    override suspend fun send(intent: WorktreeIntent.Public): SendResult = error("Unused")
}

private fun helperResultRegistry(machine: HelperResultMachine) = object : MachineRegistry {
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

private class HelperRunsHost : StudioRunHost {
    val executed = mutableListOf<StudioTurnRequest>()
    override suspend fun startedRun(id: String, at: Instant) = Unit
    override suspend fun executeRun(request: StudioTurnRequest): RunOutcome {
        executed += request
        // Crossing the native boundary, as the repository's sender does.
        request.submission?.begin()
        return RunOutcome.Completed
    }
    override suspend fun finishedRun(id: String) = Unit
}

private class HelperRunsRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())
    override suspend fun defaults(): RunSettings = DefaultRunSettings
    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome = error("Unused")
    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) = Unit
}
