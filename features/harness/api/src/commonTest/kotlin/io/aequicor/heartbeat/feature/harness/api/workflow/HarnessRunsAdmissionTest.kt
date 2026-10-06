package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class HarnessRunsAdmissionTest {
    private val spec = HarnessRunsMachineSpec
    private val run = workflowRun()
    private val start = HarnessRunsIntent.Public.Start(Request, run, Now)

    @Test
    fun `startup retry and unavailable requests preserve suspension`() {
        for (suspended in listOf(false, true)) {
            val idle = HarnessRunsState.Idle(suspended)
            val loading = HarnessRunsState.Loading(suspended)
            val failed = HarnessRunsState.Failed(suspended)
            for (state in listOf(idle, failed)) {
                spec.assertTransition(
                    state,
                    HarnessRunsIntent.Internal.Start,
                    loading,
                    listOf(HarnessRunsEffect.Restore),
                )
            }
            for (state in listOf(idle, loading, failed)) {
                spec.assertTransition(
                    state,
                    start,
                    state,
                    outputs = listOf(HarnessRunsOutput.Rejected(Request, WorkflowRejection.Unavailable)),
                )
            }
            spec.assertTransition(loading, HarnessRunsIntent.Internal.RestoreFailed, failed)
        }
        assertEquals(
            HarnessRunsIntent.Internal.RestoreFailed,
            spec.onEffectFailure(HarnessRunsEffect.Restore, Exception()),
        )
        assertEquals(null, spec.onEffectFailure(HarnessRunsEffect.StopRun(run), Exception()))
        assertEquals(null, spec.onEffectFailure(HarnessRunsEffect.Drive(listOf(run)), Exception()))
        assertEquals(null, spec.onEffectFailure(HarnessRunsEffect.Pause(listOf(run)), Exception()))
    }

    @Test
    fun `toggle during loading survives restore and all nonready states ignore duplicate toggle`() {
        val states = listOf(HarnessRunsState.Idle(), HarnessRunsState.Loading(), HarnessRunsState.Failed())
        val suspended = listOf(
            HarnessRunsState.Idle(true),
            HarnessRunsState.Loading(true),
            HarnessRunsState.Failed(true),
        )
        for ((before, after) in states.zip(suspended)) {
            spec.assertTransition(before, HarnessRunsIntent.Internal.Suspended, after)
            spec.assertIgnored(after, HarnessRunsIntent.Internal.Suspended)
            spec.assertTransition(after, HarnessRunsIntent.Internal.Resumed, before)
            spec.assertIgnored(before, HarnessRunsIntent.Internal.Resumed)
        }
        val restored = run.copy(driverGeneration = 1)
        for (isSuspended in listOf(false, true)) {
            spec.assertTransition(
                HarnessRunsState.Loading(isSuspended),
                HarnessRunsIntent.Internal.Restored(listOf(run, run), Now),
                HarnessRunsState.Failed(isSuspended),
            )
            spec.assertTransition(
                HarnessRunsState.Loading(isSuspended),
                HarnessRunsIntent.Internal.Restored(listOf(run), Now),
                HarnessRunsState.Ready(listOf(restored), isSuspended),
                effects = if (isSuspended) emptyList() else listOf(HarnessRunsEffect.Drive(listOf(restored))),
            )
        }
    }

    @Test
    fun `start admits once and rejects duplicate capacity invalid and suspended requests`() {
        val ready = HarnessRunsState.Ready()
        val active = ready.copy(runs = listOf(run))
        val result = spec.assertTransition(
            ready,
            start,
            active,
            listOf(HarnessRunsEffect.Drive(listOf(run))),
            listOf(HarnessRunsOutput.Started(Request, run.id)),
        )
        assertFalse(result.isStateChange)
        val cases = listOf(
            active to WorkflowRejection.Duplicate,
            ready.copy(isSuspended = true) to WorkflowRejection.Unavailable,
            ready.copy(runs = (1..4).map { workflowRun("wf_$it") }) to WorkflowRejection.Capacity,
        )
        for ((state, rejection) in cases) {
            spec.assertTransition(state, start, state, outputs = listOf(HarnessRunsOutput.Rejected(Request, rejection)))
        }
        for (invalid in listOf(
            start.copy(at = Now + 6.hours),
            start.copy(at = Now - 1.hours),
            start.copy(run = run.copy(attempt = 1)),
            start.copy(run = run.copy(driverGeneration = 1)),
            start.copy(run = run.copy(steps = listOf(preparedStep()))),
            start.copy(run = run.copy(caller = null)),
        )) {
            spec.assertTransition(
                ready,
                invalid,
                ready,
                outputs = listOf(HarnessRunsOutput.Rejected(Request, WorkflowRejection.InvalidRun)),
            )
        }
    }

    @Test
    fun `cancel hides foreign ownership fences old driver and repeated request retries cleanup`() {
        val ready = HarnessRunsState.Ready(listOf(run))
        val cancel = HarnessRunsIntent.Public.Cancel(Request, run.id, WorkflowViewer.Agent(Caller))
        for (intent in listOf(
            cancel.copy(run = RunId("wf_missing")),
            cancel.copy(by = WorkflowViewer.Agent(Caller.copy(nativeId = "foreign"))),
        )) {
            spec.assertTransition(
                ready,
                intent,
                ready,
                outputs = listOf(HarnessRunsOutput.Rejected(Request, WorkflowRejection.NotFound)),
            )
        }
        val cancelled = run.copy(driverGeneration = 1, cancellation = WorkflowFailure.Cancelled)
        val pending = HarnessRunsState.Ready(listOf(cancelled))
        val effects = listOf(HarnessRunsEffect.StopRun(cancelled))
        val outputs = listOf(HarnessRunsOutput.CancellationRequested(Request, run.id))
        spec.assertTransition(ready, cancel, pending, effects, outputs)
        spec.assertTransition(pending, cancel, pending, effects, outputs)
        spec.assertTransition(ready, cancel.copy(by = WorkflowViewer.User), pending, effects, outputs)
        spec.assertIgnored(pending, HarnessRunsIntent.Internal.Failed(run.id, 0, WorkflowFailure.Cancelled, Now))
        spec.assertIgnored(pending, HarnessRunsIntent.Internal.Failed(run.id, 1, WorkflowFailure.Error, Now))
        val status = WorkflowStatus.Failed(WorkflowFailure.Cancelled)
        val terminal = HarnessRunsState.Ready(listOf(cancelled.copy(status = status, finishedAt = Now)))
        spec.assertTransition(
            pending,
            HarnessRunsIntent.Internal.Failed(run.id, 1, WorkflowFailure.Cancelled, Now),
            terminal,
            outputs = listOf(HarnessRunsOutput.RunFinished(run.id, status)),
        )
        spec.assertTransition(
            terminal,
            cancel,
            terminal,
            outputs = listOf(HarnessRunsOutput.Rejected(Request, WorkflowRejection.AlreadyFinished)),
        )
    }

    @Test
    fun `suspension advances generation without terminalizing and resume restores running cancellation`() {
        val ready = HarnessRunsState.Ready(listOf(run.copy(cancellation = WorkflowFailure.Cancelled)))
        val fenced = ready.runs.single().copy(driverGeneration = 1)
        val suspended = HarnessRunsState.Ready(listOf(fenced), true)
        spec.assertTransition(
            ready,
            HarnessRunsIntent.Internal.Suspended,
            suspended,
            listOf(HarnessRunsEffect.Pause(ready.runs)),
        )
        spec.assertIgnored(suspended, HarnessRunsIntent.Internal.Suspended)
        val resumed = fenced.copy(driverGeneration = 2)
        val next = HarnessRunsState.Ready(listOf(resumed))
        spec.assertTransition(
            suspended,
            HarnessRunsIntent.Internal.Resumed,
            next,
            listOf(HarnessRunsEffect.Drive(listOf(resumed))),
        )
        spec.assertIgnored(next, HarnessRunsIntent.Internal.Resumed)
        assertEquals(WorkflowStatus.Running, resumed.status)
    }

    @Test
    fun `restore retains running work and bounded newest terminal records per harness`() {
        val status = WorkflowStatus.Failed(WorkflowFailure.Error)
        val terminals = (1..23).map {
            workflowRun("wf_$it").copy(status = status, finishedAt = Now + it.hours)
        }
        val expired = workflowRun("wf_expired").copy(
            startedAt = Now - 40.days,
            deadline = Now - 40.days + 1.hours,
            status = status,
            finishedAt = Now - 31.days,
        )
        val oldRunning = expired.copy(id = RunId("wf_old_running"), status = WorkflowStatus.Running, finishedAt = null)
        val other = terminals.first().copy(
            id = RunId("wf_other"),
            harness = io.aequicor.heartbeat.feature.harness.api.HarnessId("other"),
        )
        val loaded = terminals + expired + oldRunning + other
        val kept = terminals.drop(3) + oldRunning.copy(driverGeneration = 1) + other
        spec.assertTransition(
            HarnessRunsState.Loading(),
            HarnessRunsIntent.Internal.Restored(loaded, Now + 1.days),
            HarnessRunsState.Ready(kept),
            listOf(HarnessRunsEffect.Drive(listOf(oldRunning.copy(driverGeneration = 1)))),
        )
    }
}
