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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.reflect.safeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudioTurnExecutorTest {
    @Test
    fun `recreated ready handle submits recovery only after fresh idle inspection`() = runTest {
        val f = TurnFixture()
        f.host.isIsolated = false
        f.active.state.value = ActiveSessionState.Ready()
        f.active.inspection = TurnInspection.Idle
        f.host.beforeSubmit = { f.active.state.value = ActiveSessionState.Ready(f.turn) }
        f.tools.release.complete(Unit)
        f.executor.execute(f.host, f.request.copy(recoveryRequests = listOf(RequestId("old"))))
        assertEquals(1, f.active.inspections)
        assertTrue(f.host.submitted != null)
    }

    @Test
    fun `unowned native activity cannot trigger a recovery submission`() = runTest {
        val f = TurnFixture()
        f.host.isIsolated = false
        f.active.state.value = ActiveSessionState.Ready()
        f.active.inspection = TurnInspection.Unknown
        var outcome: TurnOutcome? = null
        f.executor.execute(
            f.host,
            f.request.copy(
                recoveryRequests = listOf(RequestId("old")),
                onOutcome = { outcome = it },
            ),
        )
        assertNull(f.host.submitted)
        assertEquals(TurnOutcome.Unknown, outcome)
    }

    @Test
    fun `persisted native receipt recovers completion without submitting another turn`() = runTest {
        val f = TurnFixture()
        f.host.isIsolated = false
        f.active.state.value = ActiveSessionState.Ready()
        f.active.inspection = TurnInspection.Observed(RequestId("old"), TurnOutcome.Completed)
        var outcome: TurnOutcome? = null
        f.executor.execute(
            f.host,
            f.request.copy(
                recoveryRequests = listOf(RequestId("old")),
                recoveryCheckpoint = "native receipt",
                onOutcome = { outcome = it },
            ),
        )
        assertNull(f.host.submitted)
        assertEquals("native receipt", f.active.inspectedCheckpoint)
        assertEquals(TurnOutcome.Completed, outcome)
    }

    @Test
    fun `cancelling scheduled preparation rejects prepared worktree ownership without sending a prompt`() = runTest {
        val fixture = TurnFixture()
        val submission = StudioRunSubmission()
        val prepared = CompletableDeferred<Unit>()
        fixture.host.beforeSubmit = { prepared.await() }
        val result = async { fixture.executor.execute(fixture.host, fixture.request.copy(submission = submission)) }
        runCurrent()
        assertTrue(fixture.machine.sent.any { it is WorktreeIntent.Public.RunStarted })
        assertNull(fixture.host.submitted)
        assertTrue(submission.cancel())
        result.cancelAndJoin()
        assertTrue(fixture.machine.sent.last() is WorktreeIntent.Public.RunRejected)
        assertTrue(fixture.events.none { it == "submit" || it == "cancel" })
        prepared.complete(Unit)
        runCurrent()
        assertNull(fixture.host.submitted)
    }

    @Test
    fun `native attachment prompt uses the same request identity as prepared worktree ownership`() = runTest {
        val fixture = TurnFixture()
        assertEquals(
            fixture.turn.id,
            fixture.active.submitStudioPrompt("", null, null, ExecutorAttachments, fixture.request.request),
        )
        val sent = requireNotNull(fixture.active.submittedRequest)
        assertEquals(fixture.request.request, sent.id)
        assertEquals(
            listOf(ContentPart.Image(ExecutorAttachments[0]), ContentPart.Resource(ExecutorAttachments[1])),
            sent.parts,
        )
    }

    @Test
    fun `isolated attachment requests retain their prepared identity and acknowledge once before settlement`() =
        runTest {
            val fixture = TurnFixture()
            var acknowledgements = 0
            val request = fixture.request.copy(attachments = ExecutorAttachments, onAccepted = { acknowledgements++ })
            val result = async { fixture.executor.execute(fixture.host, request) }
            runCurrent()
            assertEquals(1, acknowledgements)
            assertEquals(request, fixture.host.submitted)
            assertEquals(ExecutorAttachments, fixture.host.submitted?.attachments)
            assertEquals(request.request, fixture.host.submitted?.request)
            assertTrue(fixture.machine.sent.any { it is WorktreeIntent.Public.RunAccepted })
            assertFalse(result.isCompleted)
            fixture.tools.release.complete(Unit)
            assertEquals(RunOutcome.Completed, result.await())
            assertEquals(1, acknowledgements)
            assertEquals(request.request, (fixture.machine.sent.last() as WorktreeIntent.Public.RunSettled).request)
        }

    @Test
    fun `attachment refusal before native acceptance reports no acknowledgement and rejects prepared ownership`() =
        runTest {
            listOf(RequestFailureReason.UnsupportedContent, RequestFailureReason.Invalid).forEach { reason ->
                val fixture = TurnFixture()
                fixture.host.isNativeSubmissionEnabled = true
                fixture.active.sendFailure = EngineException(EngineFailure.Request(reason, fixture.request.request))
                var acknowledgements = 0
                val request = fixture.request.copy(
                    attachments = ExecutorAttachments,
                    onAccepted = { acknowledgements++ },
                )
                assertEquals(RunOutcome.Failed, fixture.executor.execute(fixture.host, request))
                assertEquals(0, acknowledgements)
                assertEquals(request.prompt, fixture.host.submitted?.prompt)
                assertEquals(ExecutorAttachments, fixture.host.submitted?.attachments)
                assertNull(fixture.tools.finished)
                assertTrue(fixture.active.state.value is ActiveSessionState.Unavailable)
                assertTrue(fixture.machine.sent.last() is WorktreeIntent.Public.RunRejected)
                assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunAccepted })
            }
        }

    @Test
    fun `unknown attachment delivery retains ownership without acknowledgement until native confirmation`() = runTest {
        val fixture = TurnFixture()
        fixture.host.isNativeSubmissionEnabled = true
        fixture.active.sendFailure = EngineException(
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown, fixture.request.request),
        )
        var acknowledgements = 0
        val request = fixture.request.copy(attachments = ExecutorAttachments, onAccepted = { acknowledgements++ })
        val result = async { fixture.executor.execute(fixture.host, request) }
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(0, acknowledgements)
        assertEquals(1, fixture.active.reconciliations)
        assertEquals(listOf("started", "open", "submit"), fixture.events)
        fixture.active.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
        runCurrent()
        assertEquals(1, acknowledgements)
        assertFalse(result.isCompleted)
        fixture.active.state.value = ActiveSessionState.Ready(fixture.turn)
        runCurrent()
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Completed, result.await())
        assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunRejected })
    }

    @Test
    fun `unknown local attachment validation becomes rejection only after authoritative idle reconciliation`() =
        runTest {
            val fixture = TurnFixture()
            fixture.host.isNativeSubmissionEnabled = true
            fixture.active.sendFailure = EngineException(
                EngineFailure.Request(RequestFailureReason.OutcomeUnknown, fixture.request.request),
            )
            fixture.active.reconcile = { fixture.active.state.value = ActiveSessionState.Ready() }
            var acknowledgements = 0
            val request = fixture.request.copy(attachments = ExecutorAttachments, onAccepted = { acknowledgements++ })
            assertEquals(RunOutcome.Failed, fixture.executor.execute(fixture.host, request))
            assertEquals(0, acknowledgements)
            assertEquals(1, fixture.active.reconciliations)
            assertEquals(request.prompt, fixture.host.submitted?.prompt)
            assertEquals(ExecutorAttachments, fixture.host.submitted?.attachments)
            assertTrue(fixture.machine.sent.last() is WorktreeIntent.Public.RunRejected)
        }

    @Test
    fun `synthetic unknown terminal from idle reconciliation never acknowledges local attachment delivery`() = runTest {
        val fixture = TurnFixture()
        fixture.host.isNativeSubmissionEnabled = true
        fixture.active.sendFailure = EngineException(
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown, fixture.request.request),
        )
        fixture.active.reconcile = {
            fixture.active.state.value = ActiveSessionState.Ready(fixture.turn.copy(outcome = TurnOutcome.Unknown))
        }
        var acknowledgements = 0
        val request = fixture.request.copy(attachments = ExecutorAttachments, onAccepted = { acknowledgements++ })
        assertEquals(RunOutcome.Failed, fixture.executor.execute(fixture.host, request))
        assertEquals(0, acknowledgements)
        assertEquals(request.prompt, fixture.host.submitted?.prompt)
        assertEquals(ExecutorAttachments, fixture.host.submitted?.attachments)
        assertTrue(fixture.machine.sent.last() is WorktreeIntent.Public.RunRejected)
    }

    @Test
    fun `a local submitting turn is not a confirmed native attachment receipt`() = runTest {
        val fixture = TurnFixture()
        fixture.active.sendFailure = EngineException(
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown, fixture.request.request),
        )
        fixture.active.reconcile = {
            fixture.active.state.value = ActiveSessionState.Submitting(
                requireNotNull(fixture.active.submittedRequest),
                fixture.turn.copy(outcome = null),
            )
        }
        val sending = backgroundScope.async {
            assertFailsWith<EngineException> {
                fixture.active.submitStudioPrompt("Question", null, null, ExecutorAttachments, fixture.request.request)
            }
        }
        runCurrent()
        assertFalse(sending.isCompleted)
        fixture.active.state.value = ActiveSessionState.Ready()
        assertEquals(fixture.active.sendFailure, sending.await())
    }

    @Test
    fun `definitive attachment rejection reconciles the cached handle before a corrected retry`() = runTest {
        val fixture = TurnFixture()
        val failure = EngineException(
            EngineFailure.Request(RequestFailureReason.UnsupportedContent, fixture.request.request),
        )
        fixture.active.sendFailure = failure
        fixture.active.reconcile = { fixture.active.state.value = ActiveSessionState.Ready() }
        assertEquals(
            failure,
            assertFailsWith<EngineException> {
                fixture.active.submitStudioPrompt("Question", null, null, ExecutorAttachments, fixture.request.request)
            },
        )
        assertEquals(1, fixture.active.reconciliations)
        assertTrue(fixture.active.state.value is ActiveSessionState.Ready)
        fixture.active.sendFailure = null
        val correctedRequest = RequestId("corrected")
        assertEquals(
            fixture.turn.id,
            fixture.active.submitStudioPrompt("Question", null, null, emptyList(), correctedRequest),
        )
        assertEquals(correctedRequest, fixture.active.submittedRequest?.id)
        assertEquals(listOf(ContentPart.Text("Question")), fixture.active.submittedRequest?.parts)
    }

    @Test
    fun `definitive attachment rejection preserves its original error if native reconciliation fails`() = runTest {
        val fixture = TurnFixture()
        val failure = EngineException(EngineFailure.Request(RequestFailureReason.Invalid, fixture.request.request))
        fixture.active.sendFailure = failure
        fixture.active.reconcile = { error("Native reconciliation unavailable") }
        assertEquals(
            failure,
            assertFailsWith<EngineException> {
                fixture.active.submitStudioPrompt("Question", null, null, ExecutorAttachments, fixture.request.request)
            },
        )
        assertEquals(1, fixture.active.reconciliations)
        assertTrue(fixture.active.state.value is ActiveSessionState.Unavailable)
    }

    @Test
    fun `accepted attachment failure retains worktree ownership through cancellation and native tool cleanup`() =
        runTest {
            val fixture = TurnFixture()
            fixture.host.stopCheckFailure = IllegalStateException("StopCheckFailed")
            fixture.active.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
            var acknowledgements = 0
            val request = fixture.request.copy(attachments = ExecutorAttachments, onAccepted = { acknowledgements++ })
            val result = async { fixture.executor.execute(fixture.host, request) }
            runCurrent()
            assertEquals(1, acknowledgements)
            assertEquals(ExecutorAttachments, fixture.host.submitted?.attachments)
            assertFalse(result.isCompleted)
            assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunSettled })
            fixture.active.state.value = ActiveSessionState.Ready(fixture.turn.copy(outcome = TurnOutcome.Cancelled))
            runCurrent()
            assertFalse(result.isCompleted)
            assertEquals("finish-start", fixture.events.last())
            fixture.tools.release.complete(Unit)
            assertEquals(RunOutcome.Failed, result.await())
            assertEquals(1, acknowledgements)
            assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunRejected })
            assertEquals(fixture.active.ref to fixture.turn.id, fixture.tools.finished)
        }

    @Test
    fun `composer acknowledgement failure cannot abandon an accepted attachment turn`() = runTest {
        val fixture = TurnFixture()
        val request = fixture.request.copy(
            attachments = ExecutorAttachments,
            onAccepted = { error("ComposerUnavailable") },
        )
        val result = async { fixture.executor.execute(fixture.host, request) }
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(fixture.events.none { it == "cancel" || it == "failure" })
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Completed, result.await())
        assertTrue(fixture.machine.sent.last() is WorktreeIntent.Public.RunSettled)
    }

    @Test
    fun `ordinary checkout settlement finds its task by session and request instead of studio chat id`() = runTest {
        val fixture = TurnFixture()
        fixture.host.isIsolated = false
        fixture.machine.state.value = WorktreeState.Ready(
            mapOf(
                "main-task" to fixture.mainTask(),
                "other-task" to fixture.mainTask().copy(
                    chatId = "other-task",
                    run = fixture.mainTask().run?.copy(request = RequestId("other-request")),
                ),
            ),
        )
        val result = async { fixture.executor.execute(fixture.host, fixture.request) }
        runCurrent()
        assertTrue(fixture.machine.sent.isEmpty())
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Completed, result.await())
        assertEquals(
            listOf<WorktreeIntent.Public>(
                WorktreeIntent.Public.RunSettled(
                    "main-task",
                    fixture.request.request,
                    fixture.active.ref,
                    fixture.turn.id,
                    TurnOutcome.Completed,
                ),
            ),
            fixture.machine.sent,
        )
        assertEquals(listOf("finish-end", "refresh", "settled", "outcome"), fixture.events.takeLast(4))
    }

    @Test
    fun `stop cancels builds of the matching ordinary task without affecting a different request`() = runTest {
        val fixture = TurnFixture()
        val builds = mapOf(
            "running" to WorktreeBuildOperation("running", "test", WorktreeBuildPhase.Running),
            "queued" to WorktreeBuildOperation("queued", "test", WorktreeBuildPhase.Queued),
            "finished" to WorktreeBuildOperation("finished", "test", WorktreeBuildPhase.Completed),
        )
        fixture.machine.state.value = WorktreeState.Ready(
            mapOf(
                "main-task" to fixture.mainTask().copy(builds = builds),
                "other-task" to fixture.mainTask().copy(
                    chatId = "other-task",
                    run = fixture.mainTask().run?.copy(request = RequestId("other-request")),
                    builds = builds,
                ),
            ),
        )
        StudioWorktrees(executorRegistry(fixture.machine), fixture.tools)
            .cancelBuilds(fixture.active.ref, fixture.request.request, fixture.turn.id)
        assertEquals(
            listOf<WorktreeIntent.Public>(
                WorktreeIntent.Public.CancelBuild("main-task", "running"),
                WorktreeIntent.Public.CancelBuild("main-task", "queued"),
            ),
            fixture.machine.sent,
        )
    }

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

    fun mainTask() = WorktreeTask(
        "main-task",
        WorkspaceRef("original"),
        WorkspaceRef("original"),
        phase = WorktreePhase.Working,
        run = WorktreeRun(request.request, session = active.ref, turn = turn.id, isPrepared = true),
        isIsolated = false,
    )
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
    var isNativeSubmissionEnabled = false
    var submitted: StudioTurnRequest? = null
    var isIsolated = true
    var openCount = 0
    var beforeSubmit: suspend () -> Unit = {}
    override suspend fun isWorktree(id: String) = isIsolated
    override suspend fun openTurn(id: String, settings: RunSettings): ActiveSession {
        openCount++
        events += "open"
        openFailure?.let { throw it }
        return active
    }
    override suspend fun submitTurn(active: ActiveSession, request: StudioTurnRequest): TurnId {
        beforeSubmit()
        request.submission?.begin()
        events += "submit"
        submitted = request
        if (isNativeSubmissionEnabled) {
            return active.submitStudioPrompt(
                request.prompt,
                null,
                null,
                request.attachments,
                request.request,
            )
        }
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

private class ExecutorSession(private val turn: Turn) : ActiveSession {
    override val ref = SessionRef(EngineId("test"), SessionSourceId("local"), "session")
    override val route = ExecutionRoute(
        ref.engine,
        EngineBindingId("binding"),
        AuthSourceId("auth"),
        AuthRevision.Known("revision"),
        WorkspaceRef("isolated"),
    )
    override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready(turn))
    var submittedRequest: PromptRequest? = null
    var sendFailure: EngineException? = null
    var inspection: TurnInspection? = null
    var inspections = 0
    var inspectedCheckpoint: String? = null
    private val inspector = object : RestoresSessionTurns {
        override suspend fun checkpoint(request: RequestId): String? = null
        override suspend fun inspect(checkpoint: String?): TurnInspection {
            inspections++
            inspectedCheckpoint = checkpoint
            return checkNotNull(inspection)
        }
    }
    var reconciliations = 0
    var reconcile: suspend () -> Unit = {}
    private val prompts = object : SendsPrompts {
        override suspend fun send(request: PromptRequest): TurnId {
            submittedRequest = request
            (state.value as? ActiveSessionState.Unavailable)?.let { throw EngineException(it.failure) }
            sendFailure?.let {
                state.value = ActiveSessionState.Unavailable(it.failure, turn.copy(outcome = null))
                throw it
            }
            return turn.id
        }
    }
    private val reconciler = object : ReconcilesSession {
        override suspend fun synchronize() {
            reconciliations++
            reconcile()
        }
    }
    private val history = object : SessionHistory {
        override suspend fun page(request: HistoryPageRequest) = error("Unused")
        override fun watch(after: HistoryCheckpoint) = emptyFlow<SessionEvent>()
    }
    override val features = object : EngineFeatures {
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> = listOfNotNull(
            history,
            prompts,
            reconciler,
            inspector.takeIf { inspection != null },
        ).firstNotNullOfOrNull { key.type.safeCast(it) }
            ?.let { FeatureAccess.Available(it) } ?: FeatureAccess.Unsupported
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

            is WorktreeIntent.Public.CancelBuild -> events += "cancel-build"

            WorktreeIntent.Public.Start, WorktreeIntent.Public.RetryLoad,
            is WorktreeIntent.Public.Prepare, is WorktreeIntent.Public.TrackMainSession,
            is WorktreeIntent.Public.RunObservationLost, is WorktreeIntent.Public.TaskCompleteSignaled,
            is WorktreeIntent.Public.ChooseAction, is WorktreeIntent.Public.ActionDelivered,
            is WorktreeIntent.Public.ActionDeliveryFailed, is WorktreeIntent.Public.Recheck,
            is WorktreeIntent.Public.ProposeBuildPlan, is WorktreeIntent.Public.ApproveBuildPlan,
            is WorktreeIntent.Public.RunBuild,
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
private val ExecutorAttachments = listOf(
    ResourceRef("attachment:image", "image/png"),
    ResourceRef("attachment:notes", "text/markdown"),
)
