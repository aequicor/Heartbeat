package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.FrozenHostedTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
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
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.NoStudioHarnessAttach
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessAttach
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
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
import io.aequicor.heartbeat.feature.worktreemode.api.runPrepared
import io.aequicor.heartbeat.feature.worktreemode.api.transition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.reflect.safeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StudioTurnExecutorTest {
    @Test
    fun `helper receipts receive exact acceptance and terminal only after tools and history settle`() = runTest {
        val fixture = TurnFixture()
        val run = async { fixture.executor.execute(fixture.host, fixture.request) }
        runCurrent()
        assertEquals(listOf(fixture.request.request to fixture.turn.id), fixture.host.acceptedReceipts)
        assertTrue(fixture.host.terminalReceipts.isEmpty())
        fixture.tools.release.complete(Unit)
        run.await()
        assertEquals(
            listOf<Triple<RequestId, TurnId, TurnOutcome>>(
                Triple(fixture.request.request, fixture.turn.id, TurnOutcome.Completed),
            ),
            fixture.host.terminalReceipts,
        )
        assertEquals(listOf("finish-end", "refresh"), fixture.host.terminalEvents.takeLast(2))
        assertEquals(true, fixture.host.isTerminalHistoryCurrent)
    }

    @Test
    fun `failing receipt observers never abandon native ownership or the reported outcome`() = runTest {
        val fixture = TurnFixture()
        fixture.host.acceptedFailure = IllegalStateException("Acceptance receipt unavailable")
        fixture.host.terminalFailure = IllegalStateException("Terminal receipt unavailable")
        val run = async { fixture.executor.execute(fixture.host, fixture.request) }
        runCurrent()
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Completed, run.await())
        assertEquals(1, fixture.host.acceptedReceipts.size)
        assertEquals(1, fixture.host.terminalReceipts.size)
        assertEquals(listOf("finish-end", "refresh", "settled", "outcome"), fixture.events.takeLast(4))
    }

    @Test
    fun `failing terminal observer in recovery still settles ownership and reports the failure`() = runTest {
        val fixture = TurnFixture()
        fixture.host.stopCheckFailure = IllegalStateException("Stop check failed")
        fixture.host.terminalFailure = IllegalStateException("Terminal receipt unavailable")
        fixture.active.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
        val run = async { fixture.executor.execute(fixture.host, fixture.request) }
        runCurrent()
        fixture.active.state.value = ActiveSessionState.Ready(fixture.turn.copy(outcome = TurnOutcome.Cancelled))
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Failed, run.await())
        assertEquals(1, fixture.host.terminalReceipts.size)
        assertEquals(listOf("finish-end", "settled", "failure"), fixture.events.takeLast(3))
    }

    @Test
    fun `a failure after the terminal observer never records terminal evidence twice`() = runTest {
        val fixture = TurnFixture()
        val request = fixture.request.copy(onOutcome = { throw IllegalStateException("Graph journal unavailable") })
        val run = async { fixture.executor.execute(fixture.host, request) }
        runCurrent()
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Failed, run.await())
        assertEquals(1, fixture.host.terminalReceipts.size)
        assertEquals(listOf("settled", "failure"), fixture.events.takeLast(2))
    }

    @Test
    fun `recovered helper receipt records actual cancellation instead of generic failed run`() = runTest {
        val fixture = TurnFixture()
        fixture.host.isIsolated = false
        fixture.host.stopCheckFailure = IllegalStateException("Stop check failed")
        fixture.active.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
        val run = async { fixture.executor.execute(fixture.host, fixture.request) }
        runCurrent()
        assertTrue(fixture.host.terminalReceipts.isEmpty())
        fixture.active.state.value = ActiveSessionState.Ready(fixture.turn.copy(outcome = TurnOutcome.Cancelled))
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Failed, run.await())
        assertEquals(
            listOf<Triple<RequestId, TurnId, TurnOutcome>>(
                Triple(fixture.request.request, fixture.turn.id, TurnOutcome.Cancelled),
            ),
            fixture.host.terminalReceipts,
        )
        assertEquals(false, fixture.host.isTerminalHistoryCurrent)
        assertEquals("finish-end", fixture.host.terminalEvents.last())
    }

    @Test
    fun `adopted native turn keeps its reservation and permission observation when admission closes`() = runTest {
        val f = TurnFixture()
        f.host.isIsolated = false
        val running = f.turn.copy(outcome = null)
        f.active.state.value = ActiveSessionState.Running(running)
        f.active.inspection = TurnInspection.Observed(f.request.request, null)
        val enabled = MutableStateFlow(true)
        val coordinator = StudioRunCoordinator(ExecutorProfile(backgroundScope), Clock.System)
        var finished = false
        val host = object : StudioRunHost {
            override suspend fun startedRun(id: String, at: kotlin.time.Instant) = Unit
            override suspend fun executeRun(request: StudioTurnRequest) = f.executor.execute(f.host, request)
            override suspend fun finishedRun(id: String) {
                finished = true
            }
        }
        val result = async {
            coordinator.run(
                host,
                f.request.copy(request = RequestId("recovery"), recoveryRequests = listOf(f.request.request)),
                cancelBeforeSubmission = true,
                admission = enabled.map { if (it) ScheduledWakeAdmission.Allow else ScheduledWakeAdmission.Defer },
            )
        }
        runCurrent()
        assertNull(f.host.submitted)
        enabled.value = false
        runCurrent()
        assertFalse(result.isCompleted)
        assertFalse(finished)
        val changed = ActiveSessionState.Interrupting(running)
        f.active.state.value = changed
        runCurrent()
        assertEquals(changed, f.host.permissionStates.last())
        assertFailsWith<IllegalStateException> { coordinator.run(host, f.request) }
        f.active.state.value = ActiveSessionState.Ready(f.turn)
        f.tools.release.complete(Unit)
        assertEquals(RunOutcome.Completed, result.await())
        assertTrue(finished)
    }

    @Test
    fun `graph stop sends one cancellation then waits for terminal confirmation`() = runTest {
        val f = TurnFixture()
        val running = f.turn.copy(outcome = null)
        f.active.state.value = ActiveSessionState.Running(running)
        var cancellations = 0
        val stopped = async {
            stopGraphTurn(f.active, setOf(f.request.request.value)) {
                cancellations++
                check(f.active.state.value is ActiveSessionState.Running)
                f.active.state.value = ActiveSessionState.Interrupting(running)
            }
        }
        runCurrent()
        assertEquals(1, cancellations)
        assertFalse(stopped.isCompleted)
        f.active.state.value = ActiveSessionState.Ready(f.turn.copy(outcome = TurnOutcome.Cancelled))
        assertTrue(stopped.await())
        assertEquals(1, cancellations)
    }

    @Test
    fun `graph stop observes an already interrupting turn without cancelling again`() = runTest {
        val f = TurnFixture()
        f.active.state.value = ActiveSessionState.Interrupting(f.turn.copy(outcome = null))
        val stopped = async {
            stopGraphTurn(f.active, setOf(f.request.request.value)) { error("Duplicate cancellation") }
        }
        runCurrent()
        assertFalse(stopped.isCompleted)
        f.active.state.value = ActiveSessionState.Ready(f.turn.copy(outcome = TurnOutcome.Cancelled))
        assertTrue(stopped.await())
    }

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
        assertEquals(AgentToolDecisionMode.AutomaticSafety, sent.decisionMode)
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
            assertEquals(request, fixture.host.submitted?.copy(delivery = request.delivery))
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
    fun `the first turn of a submission connects its claimed harnesses before the prompt and releases them`() =
        runTest {
            val harnesses = RecordingHarnessAttach()
            val fixture = TurnFixture(harnesses)
            fixture.host.isIsolated = false
            fixture.host.beforeSubmit = { assertEquals(listOf("connect submission"), harnesses.calls) }
            fixture.tools.release.complete(Unit)

            fixture.executor.execute(fixture.host, fixture.request.copy(submissionId = "submission"))

            assertTrue(fixture.host.submitted != null)
            assertEquals(listOf("connect submission", "release submission"), harnesses.calls)
            // An engine declaring tools when the session opens already sees the claimed harnesses.
            assertEquals(listOf(mapOf("harness" to setOf("chosen-by-submission"))), fixture.host.opened)
            harnesses.calls.clear()
            fixture.host.beforeSubmit = {}
            fixture.executor.execute(fixture.host, fixture.request)
            assertTrue(harnesses.calls.isEmpty(), "turns of existing chats claim nothing")
            assertEquals(emptyMap(), fixture.host.opened.last())
        }

    @Test
    fun `a submission turn that fails before its prompt releases the unused claim`() = runTest {
        val harnesses = RecordingHarnessAttach()
        val fixture = TurnFixture(harnesses)
        fixture.host.openFailure = IllegalStateException("CannotOpenNativeSession")

        fixture.executor.execute(fixture.host, fixture.request.copy(submissionId = "submission"))

        assertEquals(listOf("release submission"), harnesses.calls)
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

class StudioDeferredHarnessContextTest {
    @Test
    fun `deferred harnesses recreate frozen declarations once and reuse them after failed attachment`() = runTest {
        val fixture = deferred()
        val replacement = ExecutorSession(fixture.turn, "replacement").apply {
            frozenNames = setOf("harness_list", "restricted_script", "ex_control_tabs")
        }
        fixture.host.replacement = replacement
        var attempts = 0
        val harnesses = object : StudioHarnessAttach by NoStudioHarnessAttach {
            override suspend fun restore(harnesses: Set<String>, session: SessionRef) {
                assertEquals(setOf("restricted"), harnesses)
                assertEquals(replacement.ref, session)
                attempts++
                if (attempts == 1) error("Unconfirmed attachment")
            }
        }
        fixture.host.beforeSubmit = {
            checkNotNull(fixture.host.carriedRecord).restoreCarriedHarnesses(replacement.ref, harnesses) {
                fixture.host.carriedRecord = fixture.host.carriedRecord?.copy(
                    pendingHarnesses = null,
                    pendingHarnessSource = null,
                    pendingHarnessContext = null,
                )
            }
        }
        fixture.tools.release.complete(Unit)

        assertEquals(RunOutcome.Failed, fixture.executor.execute(fixture.host, fixture.request))
        assertNull(fixture.host.submitted)
        assertEquals(listOf(fixture.active.ref), fixture.host.rollovers)
        assertEquals(replacement.ref, fixture.host.carriedRecord?.pendingHarnessContext)
        assertEquals(mapOf("harness" to setOf("restricted")), fixture.host.opened.last())
        fixture.host.carriedRecord = Json.decodeFromString(
            StudioChatRecord.serializer(),
            Json.encodeToString(StudioChatRecord.serializer(), checkNotNull(fixture.host.carriedRecord)),
        )

        assertEquals(RunOutcome.Completed, fixture.executor.execute(fixture.host, fixture.request))
        assertEquals(1, fixture.host.rollovers.size)
        assertEquals(2, attempts)
        assertNull(fixture.host.carriedRecord?.pendingHarnessContext)
        assertNull(fixture.host.carriedRecord?.pendingHarnessSource)
    }

    @Test
    fun `deferred declaration refresh refuses recovery and unknown or active native state`() = runTest {
        val inspections = listOf(TurnInspection.Unknown, TurnInspection.Observed(RequestId("other"), null))
        for (inspection in inspections) {
            val fixture = deferred()
            fixture.active.inspection = inspection
            assertEquals(RunOutcome.Failed, fixture.executor.execute(fixture.host, fixture.request))
            assertTrue(fixture.host.rollovers.isEmpty())
            assertNull(fixture.host.submitted)
        }
        val fixture = deferred()
        assertEquals(
            RunOutcome.Failed,
            fixture.executor.execute(fixture.host, fixture.request.copy(recoveryRequests = listOf(RequestId("old")))),
        )
        assertTrue(fixture.host.rollovers.isEmpty())
        assertNull(fixture.host.submitted)
    }

    @Test
    fun `empty deferred choices disabled harnesses and dynamic adapters keep the existing context`() = runTest {
        for (case in listOf("empty", "disabled", "dynamic")) {
            val fixture = deferred()
            when (case) {
                "empty" -> fixture.host.carriedRecord = fixture.host.carriedRecord?.copy(pendingHarnesses = emptySet())

                "disabled" -> fixture.host.carriedHarnesses = object : StudioHarnessAttach by NoStudioHarnessAttach {
                    override suspend fun attached(session: SessionRef): Set<String>? = null
                }

                "dynamic" -> fixture.active.frozenNames = null
            }
            fixture.tools.release.complete(Unit)
            assertEquals(RunOutcome.Completed, fixture.executor.execute(fixture.host, fixture.request))
            assertTrue(fixture.host.rollovers.isEmpty())
            assertEquals(0, fixture.active.inspections)
        }
    }

    private fun deferred(): TurnFixture = TurnFixture().apply {
        host.isIsolated = false
        active.frozenNames = setOf("ex_control_tabs")
        active.inspection = TurnInspection.Idle
        host.carriedRecord = StudioChatRecord(
            "chat",
            "Chat",
            Instant.DISTANT_PAST,
            ref = active.ref,
            pendingHarnesses = setOf("restricted"),
            pendingHarnessSource = active.ref.copy(nativeId = "original"),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StudioBrowserEnvironmentTest {
    @Test
    fun `browser disabled during rollover does not reject the local prompt`() = runTest {
        val fixture = TurnFixture()
        fixture.active.frozenNames = setOf("ex_control_tabs")
        fixture.active.inspection = TurnInspection.Idle
        val replacement = ExecutorSession(fixture.turn, "disabled").apply {
            frozenNames = setOf("ex_control_tabs")
        }
        fixture.host.replacement = replacement
        fixture.host.afterRollover = { fixture.tools.availableNames = setOf("ex_control_tabs") }
        fixture.tools.release.complete(Unit)
        val request = fixture.request.copy(userInstruction = "@browser", isUserPrompt = true)

        assertEquals(RunOutcome.Completed, fixture.executor.execute(fixture.host, request))
        assertEquals(listOf(fixture.active.ref), fixture.host.rollovers)
        assertEquals(replacement.ref to fixture.turn.id, fixture.tools.finished)
        assertTrue(fixture.tools.specificationScopes.all { it.session == null && it.declared == null })
        assertEquals(
            listOf(fixture.active.route.workspace, replacement.route.workspace),
            fixture.tools.specificationScopes.map { it.workspace },
        )
    }

    @Test
    fun `local browser mention keeps the current session when embedded tools are disabled`() = runTest {
        val fixture = TurnFixture()
        fixture.active.frozenNames = setOf("ex_control_tabs")
        fixture.active.inspection = TurnInspection.Idle
        fixture.tools.availableNames = setOf("ex_control_tabs")
        fixture.tools.release.complete(Unit)
        val request = fixture.request.copy(userInstruction = "@browser", isUserPrompt = true)

        assertEquals(RunOutcome.Completed, fixture.executor.execute(fixture.host, request))
        assertTrue(fixture.host.rollovers.isEmpty())
        assertNotNull(fixture.host.submitted)
    }

    @Test
    fun `local browser mention restores declarations even when the old session has no browser tools`() = runTest {
        val fixture = TurnFixture()
        fixture.active.frozenNames = emptySet()
        fixture.active.inspection = TurnInspection.Idle
        val replacement = ExecutorSession(fixture.turn, "embedded").apply {
            frozenNames = setOf("browser_tabs")
        }
        fixture.host.replacement = replacement
        fixture.tools.release.complete(Unit)
        val request = fixture.request.copy(userInstruction = "@browser", isUserPrompt = true)

        assertEquals(RunOutcome.Completed, fixture.executor.execute(fixture.host, request))
        assertEquals(listOf(fixture.active.ref), fixture.host.rollovers)
        assertEquals(replacement.ref to fixture.turn.id, fixture.tools.finished)
    }

    @Test
    fun `local browser mention restores embedded declarations after an external only frozen chat`() = runTest {
        val fixture = TurnFixture()
        fixture.active.frozenNames = setOf("ex_control_tabs")
        fixture.active.inspection = TurnInspection.Idle
        val replacement = ExecutorSession(fixture.turn, "embedded").apply {
            frozenNames = setOf("browser_tabs", "ex_control_tabs")
        }
        fixture.host.replacement = replacement
        fixture.tools.release.complete(Unit)
        val request = fixture.request.copy(
            prompt = "@browser open page",
            userInstruction = "@browser open page",
            isUserPrompt = true,
        )

        assertEquals(RunOutcome.Completed, fixture.executor.execute(fixture.host, request))
        assertEquals(listOf(fixture.active.ref), fixture.host.rollovers)
        assertEquals(listOf(emptyMap(), emptyMap()), fixture.host.opened)
        assertEquals(replacement.ref to fixture.turn.id, fixture.tools.finished)
    }

    @Test
    fun `local browser mention cannot replace an uncertain external context`() = runTest {
        val fixture = TurnFixture()
        fixture.host.isIsolated = false
        fixture.active.frozenNames = setOf("ex_control_tabs")
        fixture.active.inspection = TurnInspection.Unknown
        val request = fixture.request.copy(userInstruction = "@browser", isUserPrompt = true)

        assertEquals(RunOutcome.Failed, fixture.executor.execute(fixture.host, request))
        assertTrue(fixture.host.rollovers.isEmpty())
        assertNull(fixture.host.submitted)
    }

    @Test
    fun `outside turn replaces frozen embedded declarations before sending and binds the new session`() = runTest {
        val fixture = TurnFixture()
        fixture.active.frozenNames = setOf("browser_tabs", "ex_control_tabs")
        fixture.active.inspection = TurnInspection.Idle
        val replacement = ExecutorSession(fixture.turn, "replacement").apply {
            frozenNames = setOf("ex_control_tabs")
        }
        fixture.host.replacement = replacement
        val environment = ExecutorEnvironment()
        fixture.host.beforeSubmit = {
            assertEquals(replacement.ref, environment.boundSession)
            assertEquals(ActiveSessionState.Closed, fixture.active.state.value)
        }
        fixture.tools.release.complete(Unit)

        assertEquals(
            RunOutcome.Completed,
            fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)),
        )
        assertEquals(listOf(fixture.active.ref), fixture.host.rollovers)
        assertEquals(listOf(environment.preselected, environment.preselected), fixture.host.opened)
        assertEquals(listOf(fixture.active.ref, replacement.ref), environment.bindings)
        assertEquals(replacement.ref to fixture.turn.id, fixture.tools.finished)
        assertFalse(environment.isBound)
        val accepted = fixture.machine.sent.filterIsInstance<WorktreeIntent.Public.RunAccepted>().single()
        assertEquals(replacement.ref, accepted.session)
    }

    @Test
    fun `outside turn keeps an existing compatible frozen session`() = runTest {
        val fixture = TurnFixture()
        fixture.active.frozenNames = setOf("ex_control_tabs")
        fixture.tools.release.complete(Unit)
        val environment = ExecutorEnvironment()

        assertEquals(
            RunOutcome.Completed,
            fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)),
        )
        assertTrue(fixture.host.rollovers.isEmpty())
        assertEquals(1, fixture.host.openCount)
        assertFalse(environment.isBound)
    }

    @Test
    fun `outside declaration change never replaces active or uncertain native execution`() = runTest {
        val fixture = TurnFixture()
        fixture.host.isIsolated = false
        fixture.active.frozenNames = setOf("browser_tabs")
        val running = fixture.turn.copy(outcome = null)
        val states = listOf(
            ActiveSessionState.Running(running),
            ActiveSessionState.Unavailable(EngineFailure.Unknown(), running),
            ActiveSessionState.Ready(fixture.turn.copy(outcome = TurnOutcome.Unknown)),
        )
        for (state in states) {
            fixture.active.state.value = state
            val environment = ExecutorEnvironment()
            assertEquals(
                RunOutcome.Failed,
                fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)),
            )
            assertEquals(state, fixture.active.state.value)
            assertFalse(environment.isBound)
        }
        assertTrue(fixture.host.rollovers.isEmpty())
        assertNull(fixture.host.submitted)
    }

    @Test
    fun `failed history carry refuses outside turn without sending to the old declarations`() = runTest {
        val fixture = TurnFixture()
        fixture.host.isIsolated = false
        fixture.active.frozenNames = setOf("browser_tabs")
        fixture.active.inspection = TurnInspection.Idle
        fixture.host.rolloverFailure = IllegalStateException("History unavailable")
        val environment = ExecutorEnvironment()
        assertEquals(
            RunOutcome.Failed,
            fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)),
        )
        assertEquals(ActiveSessionState.Ready(fixture.turn), fixture.active.state.value)
        assertFalse(environment.isBound)
        assertNull(fixture.host.submitted)
        assertEquals(1, fixture.host.openCount)
    }

    @Test
    fun `cached idle session cannot roll over when native inspection is unknown or running`() = runTest {
        for (inspection in listOf(TurnInspection.Unknown, TurnInspection.Observed(RequestId("other"), null))) {
            val fixture = TurnFixture()
            fixture.host.isIsolated = false
            fixture.active.frozenNames = setOf("browser_tabs")
            fixture.active.inspection = inspection
            val environment = ExecutorEnvironment()

            assertEquals(
                RunOutcome.Failed,
                fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)),
            )
            assertEquals(1, fixture.active.inspections)
            assertTrue(fixture.host.rollovers.isEmpty())
            assertNull(fixture.host.submitted)
            assertFalse(environment.isBound)
        }
    }

    @Test
    fun `closed sender before submission releases outside routing without waiting for reconciliation`() = runTest {
        val fixture = TurnFixture()
        val environment = ExecutorEnvironment()
        fixture.host.isNativeSubmissionEnabled = true
        fixture.active.state.value = ActiveSessionState.Closed
        fixture.active.senderFailure = EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed)

        assertEquals(
            RunOutcome.Failed,
            fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)),
        )
        assertFalse(environment.isBound)
        assertNull(fixture.active.submittedRequest)
        assertEquals(0, fixture.active.reconciliations)
    }

    @Test
    fun `outside environment reaches declarations and stays bound until tool cleanup completes`() = runTest {
        val fixture = TurnFixture()
        var bound = false
        val environment = object : StudioChatEnvironment {
            override val preselected = mapOf("external_browser" to setOf("browser"))
            override fun bind(session: SessionRef): AutoCloseable {
                assertEquals(fixture.active.ref, session)
                bound = true
                return AutoCloseable { bound = false }
            }
        }
        fixture.host.beforeSubmit = { assertTrue(bound) }
        val run = async { fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)) }
        runCurrent()
        assertTrue(bound)
        assertEquals(listOf(environment.preselected), fixture.host.opened)
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Completed, run.await())
        assertFalse(bound)
    }

    @Test
    fun `interrupted native submission retains outside restrictions until its outcome is known`() = runTest {
        val fixture = TurnFixture()
        var bound = false
        val environment = object : StudioChatEnvironment {
            override fun bind(session: SessionRef): AutoCloseable {
                bound = true
                return AutoCloseable { bound = false }
            }
        }
        fixture.host.isNativeSubmissionEnabled = true
        fixture.active.sendFailure = EngineException(
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown, fixture.request.request),
        )
        val run = async { fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)) }
        runCurrent()
        run.cancelAndJoin()
        assertTrue(bound)
    }

    @Test
    fun `preparation failure releases outside routing before the next desktop turn`() = runTest {
        val fixture = TurnFixture()
        val environment = ExecutorEnvironment()
        fixture.host.beforeSubmit = { error("Configuration rejected") }
        assertEquals(
            RunOutcome.Failed,
            fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)),
        )
        assertFalse(environment.isBound)
        fixture.host.beforeSubmit = { assertFalse(environment.isBound) }
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Completed, fixture.executor.execute(fixture.host, fixture.request))
    }

    @Test
    fun `proven native rejection releases outside routing including reconciled unknown delivery`() = runTest {
        val failures = listOf(
            EngineFailure.Request(RequestFailureReason.Invalid),
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown),
            EngineFailure.Unknown(),
        )
        for (failure in failures) {
            val fixture = TurnFixture()
            val environment = ExecutorEnvironment()
            fixture.host.isNativeSubmissionEnabled = true
            fixture.active.sendFailure = EngineException(failure)
            fixture.active.reconcile = { fixture.active.state.value = ActiveSessionState.Ready() }
            assertEquals(
                RunOutcome.Failed,
                fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)),
            )
            assertFalse(environment.isBound)
            fixture.active.sendFailure = null
            fixture.host.beforeSubmit = {
                assertFalse(environment.isBound)
                fixture.active.state.value = ActiveSessionState.Ready(fixture.turn)
            }
            fixture.tools.release.complete(Unit)
            assertEquals(RunOutcome.Completed, fixture.executor.execute(fixture.host, fixture.request))
        }
    }

    @Test
    fun `unclassified send failure retains outside routing until native evidence and cleanup arrive`() = runTest {
        val fixture = TurnFixture()
        val environment = ExecutorEnvironment()
        fixture.host.isNativeSubmissionEnabled = true
        fixture.active.sendFailure = EngineException(EngineFailure.Unknown())
        val run = async { fixture.executor.execute(fixture.host, fixture.request.copy(environment = environment)) }
        runCurrent()
        assertTrue(environment.isBound)
        assertFalse(run.isCompleted)
        assertTrue(fixture.host.acceptedReceipts.isEmpty())

        fixture.active.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
        runCurrent()
        assertEquals(listOf(fixture.request.request to fixture.turn.id), fixture.host.acceptedReceipts)
        assertTrue(environment.isBound)
        assertFalse(run.isCompleted)

        fixture.active.state.value = ActiveSessionState.Ready(fixture.turn)
        runCurrent()
        assertTrue(environment.isBound)
        assertFalse(run.isCompleted)
        assertTrue("finish-start" in fixture.events)
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Completed, run.await())
        assertFalse(environment.isBound)
        assertEquals(fixture.active.ref to fixture.turn.id, fixture.tools.finished)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StudioClosedTurnExecutorTest {
    @Test
    fun `closing an accepted turn ends observation without confirming native completion or releasing its worktree`() =
        runTest {
            val fixture = TurnFixture()
            fixture.active.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
            var outcome: TurnOutcome? = null
            val run = async {
                fixture.executor.execute(fixture.host, fixture.request.copy(onOutcome = { outcome = it }))
            }
            runCurrent()
            fixture.active.state.value = ActiveSessionState.Closed
            runCurrent()
            assertFalse(run.isCompleted)
            assertTrue(fixture.machine.sent.last() is WorktreeIntent.Public.RunObservationLost)
            fixture.tools.release.complete(Unit)
            assertEquals(RunOutcome.Failed, run.await())
            assertEquals(TurnOutcome.Unknown, outcome)
            assertEquals(fixture.active.ref to fixture.turn.id, fixture.tools.finished)
            assertTrue(fixture.host.terminalReceipts.isEmpty())
            assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunSettled })
            assertTrue(fixture.events.none { it == "refresh" || it == "cancel" })
        }

    @Test
    fun `closing during cancellation recovery ends both bounded and continued observation without settlement`() =
        runTest {
            for (elapsed in listOf(0L, 30_001L)) {
                val fixture = TurnFixture()
                fixture.host.stopCheckFailure = IllegalStateException("Stop check failed")
                fixture.active.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
                var outcome: TurnOutcome? = null
                val run = async {
                    fixture.executor.execute(fixture.host, fixture.request.copy(onOutcome = { outcome = it }))
                }
                runCurrent()
                advanceTimeBy(elapsed)
                runCurrent()
                assertFalse(run.isCompleted)
                fixture.active.state.value = ActiveSessionState.Closed
                fixture.tools.release.complete(Unit)
                assertEquals(RunOutcome.Failed, run.await())
                assertEquals(TurnOutcome.Unknown, outcome)
                assertEquals(fixture.active.ref to fixture.turn.id, fixture.tools.finished)
                assertTrue(fixture.host.terminalReceipts.isEmpty())
                assertEquals(1, fixture.machine.sent.count { it is WorktreeIntent.Public.RunObservationLost })
                assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunSettled })
            }
        }

    @Test
    fun `closing during uncertain submission ends the run without rejecting or settling pending worktree ownership`() =
        runTest {
            for (environment in listOf(null, ExecutorEnvironment())) {
                val fixture = TurnFixture()
                fixture.host.isNativeSubmissionEnabled = true
                fixture.active.sendFailure = EngineException(
                    EngineFailure.Request(RequestFailureReason.OutcomeUnknown, fixture.request.request),
                )
                var outcome: TurnOutcome? = null
                val run = async {
                    fixture.executor.execute(
                        fixture.host,
                        fixture.request.copy(environment = environment, onOutcome = { outcome = it }),
                    )
                }
                runCurrent()
                assertFalse(run.isCompleted)
                fixture.active.state.value = ActiveSessionState.Closed
                assertEquals(RunOutcome.Failed, run.await())
                assertEquals(TurnOutcome.Unknown, outcome)
                environment?.let { assertTrue(it.isBound) }
                assertTrue(fixture.host.submitted?.delivery?.isPending == true)
                assertNull(fixture.tools.finished)
                assertTrue(fixture.host.acceptedReceipts.isEmpty())
                assertTrue(fixture.host.terminalReceipts.isEmpty())
                assertTrue(fixture.machine.sent.any { it is WorktreeIntent.Public.RunStarted })
                val task = (fixture.machine.state.value as WorktreeState.Ready).tasks.getValue("chat")
                assertEquals(WorktreePhase.RecoveryRequired, task.phase)
                assertEquals(fixture.active.ref, task.run?.session)
                assertNull(task.run?.turn)
                assertNull(task.run?.outcome)
                assertTrue(fixture.machine.sent.any { it is WorktreeIntent.Public.RunSubmissionObservationLost })
                assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunRejected })
                assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunSettled })
            }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StudioSubmissionRecoveryTest {
    @Test
    fun `closed uncertain submission reconciles its exact terminal turn before a new worktree request`() = runTest {
        val request = RequestId("request")
        val terminalEvidence = listOf(
            TurnOutcome.Completed to TurnInspection.Observed(request, TurnOutcome.Completed),
            TurnOutcome.Unknown to TurnInspection.Observed(request, TurnOutcome.Unknown),
            TurnOutcome.Unknown to TurnInspection.Idle,
        )
        for ((priorOutcome, inspection) in terminalEvidence) {
            val fixture = TurnFixture()
            fixture.host.isNativeSubmissionEnabled = true
            fixture.active.sendFailure = EngineException(
                EngineFailure.Request(RequestFailureReason.OutcomeUnknown, fixture.request.request),
            )
            val first = async { fixture.executor.execute(fixture.host, fixture.request) }
            runCurrent()
            fixture.active.state.value = ActiveSessionState.Closed
            assertEquals(RunOutcome.Failed, first.await())
            val retry = fixture.request.copy(request = RequestId("retry"))
            fixture.active.sendFailure = null
            fixture.active.state.value = ActiveSessionState.Ready(fixture.turn.copy(outcome = priorOutcome))
            fixture.active.inspection = inspection
            fixture.host.beforeSubmit = {
                fixture.active.state.value = ActiveSessionState.Ready(fixture.turn.copy(request = retry.request))
            }
            fixture.tools.release.complete(Unit)
            assertEquals(RunOutcome.Completed, fixture.executor.execute(fixture.host, retry))
            assertEquals(0L, testScheduler.currentTime)
            assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunRejected })
            val settled = fixture.machine.sent.filterIsInstance<WorktreeIntent.Public.RunSettled>()
            assertEquals(listOf(fixture.request.request, retry.request), settled.map { it.request })
            assertEquals(priorOutcome, settled.first().outcome)
            assertEquals(listOf(retry.request to fixture.turn.id), fixture.host.acceptedReceipts)
        }
    }

    @Test
    fun `fresh native idle permits a new request without a false submission receipt`() = runTest {
        val fixture = TurnFixture()
        val pending = fixture.mainTask().copy(
            chatId = fixture.request.id,
            phase = WorktreePhase.RecoveryRequired,
            run = WorktreeRun(fixture.request.request, session = fixture.active.ref, isPrepared = true),
            isIsolated = true,
        )
        fixture.machine.state.value = WorktreeState.Ready(mapOf(pending.chatId to pending))
        fixture.active.state.value = ActiveSessionState.Ready()
        fixture.active.inspection = TurnInspection.Idle
        val retry = fixture.request.copy(request = RequestId("retry"))
        fixture.host.beforeSubmit = {
            fixture.active.state.value = ActiveSessionState.Ready(fixture.turn.copy(request = retry.request))
        }
        fixture.tools.release.complete(Unit)
        assertEquals(RunOutcome.Completed, fixture.executor.execute(fixture.host, retry))
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, fixture.active.inspections)
        assertTrue(fixture.machine.sent.none { it is WorktreeIntent.Public.RunRejected })
        assertEquals(
            listOf(
                WorktreeIntent.Public.RunSubmissionReconciled(
                    pending.chatId,
                    fixture.request.request,
                    fixture.active.ref,
                ),
            ),
            fixture.machine.sent.filterIsInstance<WorktreeIntent.Public.RunSubmissionReconciled>(),
        )
        assertEquals(
            listOf(retry.request),
            fixture.machine.sent.filterIsInstance<WorktreeIntent.Public.RunSettled>().map { it.request },
        )
    }

    @Test
    fun `recovery immediately refuses unrelated or unconfirmed native evidence`() = runTest {
        val evidenceCases = listOf(
            "foreign session",
            "idle",
            "other request",
            "unknown outcome",
            "unknown inspection",
            "other inspected request",
            "idle while running",
            "idle while unavailable with active turn",
        )
        for (evidence in evidenceCases) {
            val fixture = TurnFixture()
            val session = if (evidence == "foreign session") {
                SessionRef(EngineId("test"), SessionSourceId("local"), "other")
            } else {
                fixture.active.ref
            }
            val pending = fixture.mainTask().copy(
                chatId = fixture.request.id,
                phase = WorktreePhase.RecoveryRequired,
                run = WorktreeRun(fixture.request.request, session = session, isPrepared = true),
                isIsolated = true,
            )
            fixture.machine.state.value = WorktreeState.Ready(mapOf(pending.chatId to pending))
            fixture.active.inspection = when (evidence) {
                "idle while running", "idle while unavailable with active turn" -> TurnInspection.Idle
                "unknown inspection" -> TurnInspection.Unknown
                "other inspected request" -> TurnInspection.Observed(RequestId("other"), TurnOutcome.Completed)
                else -> null
            }
            fixture.active.state.value = when (evidence) {
                "idle while running" -> ActiveSessionState.Running(fixture.turn.copy(outcome = null))

                "idle while unavailable with active turn" -> ActiveSessionState.Unavailable(
                    EngineFailure.Request(RequestFailureReason.OutcomeUnknown, fixture.request.request),
                    fixture.turn.copy(outcome = null),
                )

                "idle" -> ActiveSessionState.Ready()

                "other request" -> ActiveSessionState.Ready(fixture.turn.copy(request = RequestId("other")))

                "unknown outcome" -> ActiveSessionState.Ready(fixture.turn.copy(outcome = TurnOutcome.Unknown))

                else -> ActiveSessionState.Ready(fixture.turn)
            }
            assertEquals(
                RunOutcome.Failed,
                fixture.executor.execute(fixture.host, fixture.request.copy(request = RequestId("retry"))),
            )
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(pending, (fixture.machine.state.value as WorktreeState.Ready).tasks.getValue(pending.chatId))
            assertTrue(fixture.machine.sent.isEmpty())
            assertNull(fixture.host.submitted)
            assertNull(fixture.tools.finished)
        }
    }
}

private class TurnFixture(harnesses: StudioHarnessAttach = NoStudioHarnessAttach) {
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
    val executor = StudioTurnExecutor(StudioWorktrees(executorRegistry(machine), tools), tools, host, harnesses)

    fun mainTask() = WorktreeTask(
        "main-task",
        WorkspaceRef("original"),
        WorkspaceRef("original"),
        phase = WorktreePhase.Working,
        run = WorktreeRun(request.request, session = active.ref, turn = turn.id, isPrepared = true),
        isIsolated = false,
    )
}

private class RecordingHarnessAttach : StudioHarnessAttach {
    val calls = mutableListOf<String>()
    override fun preselected(submissionId: String) = mapOf("harness" to setOf("chosen-by-$submissionId"))
    override suspend fun beforeSubmit(submissionId: String, session: SessionRef) {
        calls += "connect $submissionId"
    }
    override suspend fun attached(session: SessionRef): Set<String> = emptySet()
    override suspend fun restore(harnesses: Set<String>, session: SessionRef) = Unit
    override fun release(submissionId: String) {
        calls += "release $submissionId"
    }
}

private class ExecutorTools(private val events: MutableList<String>) : ProfileAgentTools by NoAgentTools {
    var availableNames = setOf("browser_tabs", "ex_control_tabs")
    val specificationScopes = mutableListOf<AgentToolScope>()
    override suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> {
        specificationScopes += scope
        return specifications(scope.workspace)
    }
    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> = availableNames.map {
        AgentToolSpec(it, it, kotlinx.serialization.json.JsonObject(emptyMap()))
    }

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
    private var active: ExecutorSession,
    private val turn: Turn,
    private val events: MutableList<String>,
) : StudioTurnHost,
    StudioTurnObserver {
    var openFailure: Exception? = null
    var replacement: ExecutorSession? = null
    var carriedRecord: StudioChatRecord? = null
    var carriedHarnesses: StudioHarnessAttach = NoStudioHarnessAttach
    var rolloverFailure: Exception? = null
    var afterRollover: () -> Unit = {}
    val rollovers = mutableListOf<SessionRef>()
    var stopCheckFailure: Exception? = null
    var isNativeSubmissionEnabled = false
    var submitted: StudioTurnRequest? = null
    var isIsolated = true
    var openCount = 0
    val opened = mutableListOf<Map<String, Set<String>>>()
    val permissionStates = mutableListOf<ActiveSessionState>()
    var beforeSubmit: suspend () -> Unit = {}
    val acceptedReceipts = mutableListOf<Pair<RequestId, TurnId>>()
    val terminalReceipts = mutableListOf<Triple<RequestId, TurnId, TurnOutcome>>()
    var terminalEvents = emptyList<String>()
    var isTerminalHistoryCurrent: Boolean? = null
    var acceptedFailure: Exception? = null
    var terminalFailure: Exception? = null
    override suspend fun acceptedTurn(request: StudioTurnRequest, active: ActiveSession, turn: TurnId) {
        acceptedReceipts += request.request to turn
        acceptedFailure?.let { throw it }
    }
    override suspend fun terminalTurn(
        request: StudioTurnRequest,
        active: ActiveSession,
        turn: TurnId,
        outcome: TurnOutcome,
        isHistoryCurrent: Boolean,
    ) {
        terminalReceipts += Triple(request.request, turn, outcome)
        terminalEvents = events.toList()
        isTerminalHistoryCurrent = isHistoryCurrent
        terminalFailure?.let { throw it }
    }
    override suspend fun isWorktree(id: String) = isIsolated
    override suspend fun openTurn(
        id: String,
        settings: RunSettings,
        preselected: Map<String, Set<String>>,
    ): ActiveSession {
        openCount++
        opened += carriedRecord?.withCarriedHarnesses(preselected) ?: preselected
        events += "open"
        openFailure?.let { throw it }
        carriedRecord?.takeIf { it.ref == null }?.let { record ->
            carriedRecord = record.withNativeReference(
                turn.target,
                active.ref,
                record.isHarnessCarryEnabled(carriedHarnesses),
            )
        }
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
                delivery = request.delivery,
            )
        }
        request.delivery?.isPending = true
        return turn.id
    }
    override suspend fun needsCarriedTools(id: String, active: ActiveSession): Boolean =
        carriedRecord?.needsHarnessDeclarations(active.ref, carriedHarnesses) == true
    override suspend fun rolloverTurn(request: StudioTurnRequest, active: ActiveSession) {
        rolloverFailure?.let { throw it }
        rollovers += active.ref
        active.close()
        this.active = checkNotNull(replacement)
        carriedRecord = carriedRecord?.rolledOver("previous context")
        afterRollover()
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
    override suspend fun updatePermissions(id: String, state: ActiveSessionState) {
        permissionStates += state
    }
    override suspend fun outcome(id: String, outcome: TurnOutcome?): RunOutcome {
        events += "outcome"
        return when (outcome) {
            TurnOutcome.Completed -> RunOutcome.Completed
            TurnOutcome.Cancelled -> RunOutcome.Stopped
            TurnOutcome.Unknown, is TurnOutcome.Failed, null -> RunOutcome.Failed
        }
    }
    override suspend fun failedTurn(id: String, error: Exception): RunOutcome {
        events += "failure"
        return RunOutcome.Failed
    }
}

private class ExecutorSession(private val turn: Turn, nativeId: String = "session") : ActiveSession {
    override val ref = SessionRef(EngineId("test"), SessionSourceId("local"), nativeId)
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
    var senderFailure: EngineFailure? = null
    var inspection: TurnInspection? = null
    var inspections = 0
    var inspectedCheckpoint: String? = null
    var frozenNames: Set<String>? = null
    private val frozenTools = object : FrozenHostedTools {
        override val names: Set<String> get() = frozenNames.orEmpty()
    }
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
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
            if (key == SendsPrompts) senderFailure?.let { return FeatureAccess.Unavailable(it) }
            return listOfNotNull(
                history,
                prompts,
                reconciler,
                inspector.takeIf { inspection != null },
                frozenTools.takeIf { frozenNames != null },
            ).firstNotNullOfOrNull { key.type.safeCast(it) }
                ?.let { FeatureAccess.Available(it) } ?: FeatureAccess.Unsupported
        }
    }
    override suspend fun close() {
        state.value = ActiveSessionState.Closed
    }
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
                        "chat" to task.transition(intent).let { started ->
                            if (isPrepared) {
                                started.runPrepared(intent.request)
                            } else {
                                started.copy(phase = WorktreePhase.Failed)
                            }
                        },
                    ),
                )
            }

            is WorktreeIntent.Public.RunAccepted -> events += "accepted"

            is WorktreeIntent.Public.RunSettled -> events += "settled"

            is WorktreeIntent.Public.RunRejected -> events += "rejected"

            is WorktreeIntent.Public.RunSubmissionObservationLost,
            is WorktreeIntent.Public.RunSubmissionReconciled,
            is WorktreeIntent.Public.RunObservationLost,
            -> Unit

            is WorktreeIntent.Public.CancelBuild -> events += "cancel-build"

            WorktreeIntent.Public.Start, WorktreeIntent.Public.RetryLoad,
            is WorktreeIntent.Public.Prepare, is WorktreeIntent.Public.TrackMainSession,
            is WorktreeIntent.Public.TaskCompleteSignaled,
            is WorktreeIntent.Public.ChooseAction, is WorktreeIntent.Public.ActionDelivered,
            is WorktreeIntent.Public.ActionDeliveryFailed, is WorktreeIntent.Public.Recheck,
            is WorktreeIntent.Public.ProposeBuildPlan, is WorktreeIntent.Public.ApproveBuildPlan,
            is WorktreeIntent.Public.RunBuild, is WorktreeIntent.Public.RunAdopted,
            is WorktreeIntent.Public.Seal,
            -> error(
                "Unexpected intent $intent",
            )
        }
        if (intent !is WorktreeIntent.Public.RunStarted) {
            val ready = state.value as WorktreeState.Ready
            state.value = WorktreeState.Ready(ready.tasks.mapValues { (_, task) -> task.transition(intent) })
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

private class ExecutorProfile(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "profile"
    override val savedState: ScopeSavedState get() = error("unused")
    override val isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
}

private class ExecutorEnvironment : StudioChatEnvironment {
    override val preselected = mapOf("external_browser" to setOf("browser"))
    override fun acceptsTool(name: String): Boolean = !name.startsWith("browser_")
    val bindings = mutableListOf<SessionRef>()
    var boundSession: SessionRef? = null
    var isBound = false
    override fun bind(session: SessionRef): AutoCloseable {
        bindings += session
        boundSession = session
        isBound = true
        return AutoCloseable {
            boundSession = null
            isBound = false
        }
    }
}
