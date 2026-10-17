package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WorktreeMachineTest {
    @Test
    fun `profile startup restores durable tasks`() {
        WorktreeMachineSpec.assertTransition(
            WorktreeState.Idle,
            WorktreeIntent.Public.Start,
            WorktreeState.Loading,
            effects = listOf(WorktreeEffect.Load),
        )
        WorktreeMachineSpec.assertTransition(
            WorktreeState.Loading,
            WorktreeIntent.Internal.Loaded(mapOf(task.chatId to task)),
            WorktreeState.Ready(mapOf(task.chatId to task)),
            effects = listOf(WorktreeEffect.ObserveWorkers),
        )
    }

    @Test
    fun `one task update preserves sibling tasks and stale revisions are ignored`() {
        val other = task.copy(chatId = "other")
        val ready = WorktreeState.Ready(mapOf(task.chatId to task, other.chatId to other))
        val changed = task.copy(revision = 2)
        WorktreeMachineSpec.assertTransition(
            ready,
            WorktreeIntent.Internal.Updated(changed),
            WorktreeState.Ready(mapOf(task.chatId to changed, other.chatId to other)),
            outputs = listOf(WorktreeOutput.Changed(task.chatId)),
        )
        WorktreeMachineSpec.assertIgnored(ready, WorktreeIntent.Internal.Updated(task))
    }

    @Test
    fun `native completion requires explicit signal from the same accepted turn`() {
        val completed = running.transition(settled)
        assertEquals(WorktreePhase.Idle, completed.phase)
        val signaled = running.transition(signal)
        assertEquals(WorktreePhase.CompletionSignaled, signaled.phase)
        assertEquals(WorktreePhase.AwaitingDecision, signaled.transition(settled).phase)
        assertEquals(WorktreePhase.AwaitingDecision, completed.transition(signal).phase)
        assertEquals(running, running.transition(signal.copy(turn = TurnId("stale"))))
    }

    @Test
    fun `unknown and cancelled outcomes cannot complete a signaled task`() {
        val signaled = running.transition(signal)
        assertEquals(
            WorktreePhase.RecoveryRequired,
            signaled.transition(settled.copy(outcome = TurnOutcome.Unknown)).phase,
        )
        assertEquals(WorktreePhase.Idle, signaled.transition(settled.copy(outcome = TurnOutcome.Cancelled)).phase)
    }

    @Test
    fun `active builds delay completion until their terminal result`() {
        val build = WorktreeBuildOperation("build", "test", WorktreeBuildPhase.Running)
        val waiting = running.copy(builds = mapOf(build.id to build)).transition(signal).transition(settled)
        assertEquals(WorktreePhase.CompletionSignaled, waiting.phase)
        assertEquals(
            WorktreePhase.AwaitingDecision,
            waiting.copy(builds = mapOf(build.id to build.copy(phase = WorktreeBuildPhase.Completed))).settle().phase,
        )
    }

    @Test
    fun `unknown worker outcome blocks completion and authoritative recovery can settle it`() {
        val build = WorktreeBuildOperation("build", "test", WorktreeBuildPhase.Running)
        val completed = running.buildUpdated(build).transition(signal).transition(settled)
        val unknown = completed.buildUpdated(build.copy(phase = WorktreeBuildPhase.Unknown))
        assertEquals(WorktreePhase.RecoveryRequired, unknown.phase)
        assertFalse(unknown.canChooseAction())
        val recovered = unknown.buildUpdated(build)
        assertEquals(WorktreePhase.CompletionSignaled, recovered.phase)
        assertNull(recovered.failure)
        val terminal = recovered.buildUpdated(build.copy(phase = WorktreeBuildPhase.Completed))
        assertEquals(WorktreePhase.AwaitingDecision, terminal.phase)
        assertEquals(terminal, terminal.buildUpdated(build))
    }

    @Test
    fun `restart preserves known native completion while waiting for a worker and clears stale observation failure`() {
        val build = WorktreeBuildOperation("build", "test", WorktreeBuildPhase.Running)
        val completed = running.buildUpdated(build).transition(signal).transition(settled)
        assertEquals(completed, completed.reconciled(true, completed.executionWorkspace, restart = true))
        val stale = completed.needsRecovery("NativeOutcomeUnknown")
        val recovered = stale.buildUpdated(build.copy(phase = WorktreeBuildPhase.Completed))
        assertEquals(WorktreePhase.AwaitingDecision, recovered.phase)
        assertNull(recovered.failure)
    }

    @Test
    fun `submission rejection releases pending run without inventing native identity`() {
        val pending = task.transition(WorktreeIntent.Public.RunStarted(task.chatId, request))
        val rejected = pending.transition(WorktreeIntent.Public.RunRejected(task.chatId, request, "Rejected"))
        assertEquals(WorktreePhase.Failed, rejected.phase)
        assertNull(rejected.run)
        assertEquals(
            WorktreePhase.Working,
            rejected.transition(WorktreeIntent.Public.RunStarted(task.chatId, RequestId("retry"))).phase,
        )
        assertEquals(running, running.transition(WorktreeIntent.Public.RunRejected(task.chatId, request, "late")))
    }

    @Test
    fun `new coding iteration clears old completion signal and accepted identity`() {
        val done = running.transition(signal).transition(settled)
        val next = done.transition(WorktreeIntent.Public.RunStarted(task.chatId, RequestId("next")))
        assertEquals(WorktreePhase.Working, next.phase)
        assertFalse(checkNotNull(next.run).isCompletionSignaled)
        assertNull(next.run.session)
        assertEquals(next, next.transition(signal))
    }

    @Test
    fun `dismissed completion cannot be reopened by a delayed old signal`() {
        val done = running.transition(signal).transition(settled)
        val refined = done.transition(WorktreeIntent.Public.ChooseAction(task.chatId, WorktreeAction.Refine))
        assertEquals(WorktreePhase.Idle, refined.phase)
        assertFalse(checkNotNull(refined.run).isCompletionSignaled)
        assertEquals(refined, refined.transition(signal))
    }

    @Test
    fun `late action preparation cannot revive a superseded claim after recovery or refine`() {
        val done = running.transition(signal).transition(settled)
        val expected = WorktreeExpectedAction("action", WorktreeRunKind.Merge)
        val claimed = done.claimAction(expected)
        val prompt = WorktreeActionRequest(expected.operation, expected.kind, "merge")
        assertEquals(expected, claimed.expectedAction)
        assertEquals(claimed, claimed.actionPrepared(prompt.copy(operation = "other")))
        val recovered = claimed.needsRecovery("OriginalCheckoutDirty").preflightRecovered()
        val refined = recovered.transition(WorktreeIntent.Public.ChooseAction(task.chatId, WorktreeAction.Refine))
        assertEquals(WorktreePhase.Idle, refined.phase)
        assertEquals(refined, refined.actionPrepared(prompt))
        val replacement = recovered.transition(WorktreeIntent.Public.RunStarted(task.chatId, RequestId("next")))
        assertEquals(replacement, replacement.actionPrepared(prompt))
        assertEquals(replacement, replacement.claimAction(expected))
    }

    @Test
    fun `delivered action permits only the matching action submission`() {
        val done = running.transition(signal).transition(settled)
        val action = done.claimAction(WorktreeExpectedAction("action", WorktreeRunKind.Merge)).actionPrepared(
            WorktreeActionRequest("action", WorktreeRunKind.Merge, "merge"),
        )
        assertFalse(action.canChooseAction())
        val delivered = action.transition(WorktreeIntent.Public.ActionDelivered(task.chatId, "action"))
        assertNull(delivered.actionRequest)
        assertEquals(delivered, delivered.transition(WorktreeIntent.Public.RunStarted(task.chatId, RequestId("other"))))
        val started = delivered.transition(
            WorktreeIntent.Public.RunStarted(task.chatId, RequestId("action"), WorktreeRunKind.Merge),
        )
        assertEquals(WorktreeRunKind.Merge, started.run?.kind)
        assertNull(started.expectedAction)
        assertEquals(
            WorktreePhase.RecoveryRequired,
            delivered.reconciled(true, task.executionWorkspace, restart = true).phase,
        )
    }

    @Test
    fun `lost native observation preserves unsettled identity and cannot authorize a replacement run`() {
        val lost = running.transition(
            WorktreeIntent.Public.RunObservationLost(task.chatId, request, session, turn, "ObserverLost"),
        )
        assertEquals(WorktreePhase.RecoveryRequired, lost.phase)
        assertNull(checkNotNull(lost.run).outcome)
        assertEquals(turn, lost.run.turn)
        assertEquals(lost, lost.transition(WorktreeIntent.Public.RunStarted(task.chatId, RequestId("replacement"))))
        assertEquals(WorktreePhase.Idle, lost.transition(settled).phase)
    }

    @Test
    fun `a restarted host adopts the recorded running turn and keeps its identity`() {
        val lost = running.reconciled(true, running.executionWorkspace, restart = true)
        assertEquals(WorktreePhase.RecoveryRequired, lost.phase)
        val adopt = WorktreeIntent.Public.RunAdopted(task.chatId, request, session, turn)
        val ready = WorktreeState.Ready(mapOf(task.chatId to lost))
        WorktreeMachineSpec.assertTransition(ready, adopt, ready, effects = listOf(WorktreeEffect.Apply(adopt)))
        val adopted = lost.transition(adopt)
        assertEquals(WorktreePhase.Working, adopted.phase)
        assertNull(adopted.failure)
        assertEquals(running.run, adopted.run)
        assertEquals(WorktreePhase.Idle, adopted.transition(settled).phase)
        val signaled = running.transition(signal).reconciled(true, running.executionWorkspace, restart = true)
        assertEquals(WorktreePhase.CompletionSignaled, signaled.transition(adopt).phase)
    }

    @Test
    fun `adoption needs the exact recorded identity of a pending coding run in recovery`() {
        val lost = running.reconciled(true, running.executionWorkspace, restart = true)
        val adopt = WorktreeIntent.Public.RunAdopted(task.chatId, request, session, turn)
        val other = SessionRef(session.engine, session.source, "other")
        assertEquals(lost, lost.transition(adopt.copy(turn = TurnId("another"))))
        assertEquals(lost, lost.transition(adopt.copy(session = other)))
        assertEquals(lost, lost.transition(adopt.copy(request = RequestId("other"))))
        assertEquals(lost, lost.transition(adopt.copy(chatId = "other")))
        assertEquals(running, running.transition(adopt))
        val finished = running.transition(settled)
        assertEquals(finished, finished.transition(adopt))
        // A run that never recorded a turn proves nothing about a live one.
        val pending = task.transition(WorktreeIntent.Public.RunStarted(task.chatId, request)).runPrepared(request)
        val unaccepted = pending.transition(
            WorktreeIntent.Public.RunSubmissionObservationLost(task.chatId, request, session, "Lost"),
        )
        assertEquals(unaccepted, unaccepted.transition(adopt))
        val action = task.transition(WorktreeIntent.Public.RunStarted(task.chatId, request, WorktreeRunKind.Merge))
            .transition(WorktreeIntent.Public.RunAccepted(task.chatId, request, session, turn))
            .needsRecovery("NativeOutcomeUnknown")
        assertEquals(action, action.transition(adopt))
    }

    @Test
    fun `a seal command is applied by the journal and changes no run or phase on its own`() {
        val seal = WorktreeIntent.Public.Seal(task.chatId, "seal-1")
        val ready = WorktreeState.Ready(mapOf(task.chatId to running))
        WorktreeMachineSpec.assertTransition(ready, seal, ready, effects = listOf(WorktreeEffect.Apply(seal)))
        assertEquals(running, running.transition(seal))
        val sealed = running.sealed(WorktreeSeal("seal-1", "abc"))
        assertEquals(WorktreeSeal("seal-1", "abc"), sealed.seal)
        assertEquals(running.run, sealed.run)
        assertEquals(running.phase, sealed.phase)
    }

    @Test
    fun `a failed seal is recorded in the task without failing the checkout or a native run`() {
        val seal = WorktreeIntent.Public.Seal(task.chatId, "seal-1")
        val failed = running.operationFailed(seal, "TurnActive")
        assertEquals(WorktreeSeal("seal-1", failure = "TurnActive"), failed.seal)
        assertEquals(running.phase, failed.phase)
        assertEquals(running.run, failed.run)
        assertNull(failed.failure)
        // A later seal replaces the earlier outcome.
        assertEquals(WorktreeSeal("seal-2", "def"), failed.sealed(WorktreeSeal("seal-2", "def")).seal)
    }

    @Test
    fun `lost submission observation requires recovery without inventing acceptance or completion`() {
        for (kind in listOf(WorktreeRunKind.Coding, WorktreeRunKind.Merge)) {
            val pending = task.transition(WorktreeIntent.Public.RunStarted(task.chatId, request, kind))
                .runPrepared(request)
            val command = WorktreeIntent.Public.RunSubmissionObservationLost(task.chatId, request, session, "Lost")
            val ready = WorktreeState.Ready(mapOf(task.chatId to pending))
            WorktreeMachineSpec.assertTransition(ready, command, ready, effects = listOf(WorktreeEffect.Apply(command)))
            val lost = pending.transition(command)
            assertEquals(WorktreePhase.RecoveryRequired, lost.phase)
            assertEquals(pending.run?.copy(session = session), lost.run)
            assertEquals("Lost", lost.failure)
            assertNull(checkNotNull(lost.run).turn)
            assertNull(lost.run.outcome)
            assertEquals(lost, lost.transition(WorktreeIntent.Public.RunStarted(task.chatId, RequestId("next"))))
            assertEquals(lost, lost.reconciled(true, lost.executionWorkspace, restart = false))
            assertEquals(WorktreePhase.Idle, lost.transition(settled.copy(outcome = TurnOutcome.Cancelled)).phase)
        }
    }

    @Test
    fun `lost submission observation ignores stale identities and already accepted or settled runs`() {
        val pending = task.transition(WorktreeIntent.Public.RunStarted(task.chatId, request))
        val command = WorktreeIntent.Public.RunSubmissionObservationLost(task.chatId, request, session, "Lost")
        assertEquals(pending, pending.transition(command.copy(chatId = "other")))
        assertEquals(pending, pending.transition(command.copy(request = RequestId("other"))))
        val bound = pending.copy(run = checkNotNull(pending.run).copy(session = session))
        val other = SessionRef(session.engine, session.source, "other")
        assertEquals(bound, bound.transition(command.copy(session = other)))
        assertEquals(running, running.transition(command))
        val finished = running.transition(settled)
        assertEquals(finished, finished.transition(command))
    }

    @Test
    fun `fresh idle reconciliation clears only its unaccepted recovery request without a terminal receipt`() {
        val pending = task.transition(WorktreeIntent.Public.RunStarted(task.chatId, request))
        val lost = pending.transition(
            WorktreeIntent.Public.RunSubmissionObservationLost(task.chatId, request, session, "Lost"),
        )
        val command = WorktreeIntent.Public.RunSubmissionReconciled(task.chatId, request, session)
        val ready = WorktreeState.Ready(mapOf(task.chatId to lost))
        WorktreeMachineSpec.assertTransition(ready, command, ready, effects = listOf(WorktreeEffect.Apply(command)))
        val reconciled = lost.transition(command)
        assertEquals(WorktreePhase.Failed, reconciled.phase)
        assertEquals("NativeOutcomeUnknown", reconciled.failure)
        assertNull(reconciled.run)
        val retry = RequestId("retry")
        assertEquals(retry, reconciled.transition(WorktreeIntent.Public.RunStarted(task.chatId, retry)).run?.request)
        assertEquals(lost, lost.transition(command.copy(chatId = "other")))
        assertEquals(lost, lost.transition(command.copy(request = RequestId("other"))))
        assertEquals(lost, lost.transition(command.copy(session = SessionRef(session.engine, session.source, "other"))))
        assertEquals(pending, pending.transition(command))
        val accepted = lost.transition(WorktreeIntent.Public.RunAccepted(task.chatId, request, session, turn))
        assertEquals(accepted, accepted.transition(command))
        val finished = accepted.transition(settled)
        assertEquals(finished, finished.transition(command))
    }

    @Test
    fun `configuration versions advance and operation snapshot retains the selected version`() {
        val plan = WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("build", "build")))
        val configured = task.transition(WorktreeIntent.Public.ProposeBuildPlan(task.chatId, plan))
        assertEquals(1, configured.buildConfigurationRevision)
        assertEquals(
            2,
            configured.transition(WorktreeIntent.Public.ProposeBuildPlan(task.chatId, plan)).buildConfigurationRevision,
        )
        val queued = WorktreeBuildOperation("build", "build", configurationRevision = 1)
        val building = configured.buildUpdated(queued)
        assertEquals(building, building.transition(WorktreeIntent.Public.ProposeBuildPlan(task.chatId, plan)))
    }

    @Test
    fun `action delivery failure requires recovery before and after handoff without replaying the prompt`() {
        val done = running.transition(signal).transition(settled)
        val action = done.claimAction(WorktreeExpectedAction("action", WorktreeRunKind.Merge)).actionPrepared(
            WorktreeActionRequest("action", WorktreeRunKind.Merge, "merge"),
        )
        val failure = WorktreeIntent.Public.ActionDeliveryFailed(task.chatId, "action", "DeliveryFailed")
        val rejected = action.transition(failure)
        assertEquals(WorktreePhase.RecoveryRequired, rejected.phase)
        assertNull(rejected.actionRequest)
        assertEquals("DeliveryFailed", rejected.failure)
        assertEquals(action.run, rejected.run)
        assertEquals(action.expectedAction, rejected.expectedAction)
        val handedOff = action.transition(WorktreeIntent.Public.ActionDelivered(task.chatId, "action"))
        assertEquals(rejected, handedOff.transition(failure))
    }

    @Test
    fun `stale delivery failure cannot replace another operation or a live native run`() {
        val done = running.transition(signal).transition(settled)
        val action = done.claimAction(WorktreeExpectedAction("action", WorktreeRunKind.Merge)).actionPrepared(
            WorktreeActionRequest("action", WorktreeRunKind.Merge, "merge"),
        )
        val failure = WorktreeIntent.Public.ActionDeliveryFailed(task.chatId, "action", "DeliveryFailed")
        assertEquals(action, action.transition(failure.copy(operation = "stale")))
        assertEquals(action, action.transition(failure.copy(chatId = "other")))
        val active = action.copy(run = WorktreeRun(RequestId("action"), WorktreeRunKind.Merge, session, turn))
        assertEquals(active, active.transition(failure))
        assertNull(checkNotNull(active.run).outcome)
    }

    private companion object {
        val request = RequestId("request")
        val session = SessionRef(EngineId("test"), SessionSourceId("test"), "native")
        val turn = TurnId("turn")
        val task = WorktreeTask(
            "chat",
            WorkspaceRef("project"),
            executionWorkspace = WorkspaceRef("checkout"),
            phase = WorktreePhase.Idle,
        )
        val running = task.transition(WorktreeIntent.Public.RunStarted(task.chatId, request))
            .transition(WorktreeIntent.Public.RunAccepted(task.chatId, request, session, turn))
        val signal = WorktreeIntent.Public.TaskCompleteSignaled(task.chatId, session, turn, "Done")
        val settled = WorktreeIntent.Public.RunSettled(task.chatId, request, session, turn, TurnOutcome.Completed)
    }
}
