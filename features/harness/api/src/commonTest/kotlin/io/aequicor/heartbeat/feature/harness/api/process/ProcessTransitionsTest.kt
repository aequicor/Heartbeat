package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessTransitionsTest {
    private val answer = WakeCondition(events = setOf(EventKeys.custom("process.answer")))

    @Test
    fun `a new process is idle with no stage and no executor`() {
        val process = newProcess()
        assertEquals(ProcessPhase.Idle, process.phase)
        assertNull(process.stage)
        assertNull(process.inFlight)
        assertEquals(1, process.nextAttempt)
    }

    @Test
    fun `start attaches an executor bound to the current input and subject`() {
        val process = newProcess()
            .change(ProcessCommand.ReviseInput(3))
            .change(ProcessCommand.ObserveSubject(sha('a')))
            .change(ProcessCommand.Start(buildStage, helperExecutor), seconds = 5)

        val attempt = assertIs<ProcessPhase.Running>(process.phase).attempt
        assertEquals(ProcessAttempt(1, buildStage, helperExecutor, 3, sha('a'), processAt(5)), attempt)
        assertEquals(buildStage, process.stage)
        assertEquals(2, process.nextAttempt)
        assertEquals(processAt(5), process.updatedAt)
    }

    @Test
    fun `finishing frees the executor and keeps the evidence`() {
        val process = newProcess().attempt(planStage, succeeded("plan-1"))

        assertEquals(ProcessPhase.Idle, process.phase)
        assertNull(process.inFlight)
        assertEquals(planStage, process.stage)
        assertEquals(evidence("plan-1"), process.currentResult(planStage))
        assertEquals(1, process.history.size)
    }

    @Test
    fun `a second start while an attempt runs is rejected`() {
        val running = newProcess().change(ProcessCommand.Start(planStage, helperExecutor))
        running.expectRejected(ProcessCommand.Start(planStage, helperExecutor), ProcessRejection.AttemptInFlight)
        running.expectRejected(ProcessCommand.Start(buildStage, helperExecutor), ProcessRejection.AttemptInFlight)
    }

    @Test
    fun `a wait cannot begin while an executor is attached`() {
        val running = newProcess().change(ProcessCommand.Start(planStage, helperExecutor))
        running.expectRejected(ProcessCommand.Wait(WaitReason.Human, answer), ProcessRejection.AttemptInFlight)
    }

    @Test
    fun `a waiting process owns no executor and records what it waits for`() {
        val waiting = newProcess()
            .attempt(planStage, succeeded("plan-1"))
            .change(ProcessCommand.Wait(WaitReason.Human, answer), seconds = 10)

        val wait = assertIs<ProcessPhase.Waiting>(waiting.phase).wait
        assertEquals(ProcessWait(1, WaitReason.Human, answer, processAt(10)), wait)
        assertNull(waiting.inFlight)
        assertEquals(2, waiting.nextWait)
    }

    @Test
    fun `a waiting process must resume before it starts again`() {
        val waiting = newProcess().change(ProcessCommand.Wait(WaitReason.External, answer))
        waiting.expectRejected(ProcessCommand.Start(planStage, helperExecutor), ProcessRejection.WaitPending)
        waiting.expectRejected(ProcessCommand.Wait(WaitReason.Timer, answer), ProcessRejection.WaitPending)

        val resumed = waiting.change(ProcessCommand.Resume(1))
        assertEquals(ProcessPhase.Idle, resumed.phase)
        resumed.change(ProcessCommand.Start(planStage, helperExecutor))
    }

    @Test
    fun `resume of a wait that never existed is rejected`() {
        newProcess().expectRejected(ProcessCommand.Resume(1), ProcessRejection.UnknownWait)
        newProcess().expectRejected(ProcessCommand.Resume(0), ProcessRejection.UnknownWait)
        newProcess().change(ProcessCommand.Start(planStage, helperExecutor))
            .expectRejected(ProcessCommand.Resume(1), ProcessRejection.UnknownWait)
        newProcess().change(ProcessCommand.Wait(WaitReason.Human, answer))
            .expectRejected(ProcessCommand.Resume(2), ProcessRejection.UnknownWait)
    }

    @Test
    fun `a duplicate resume of a finished wait changes nothing and cannot end a newer wait`() {
        val resumed = newProcess().change(ProcessCommand.Wait(WaitReason.Human, answer))
            .change(ProcessCommand.Resume(1))
        resumed.expectUnchanged(ProcessCommand.Resume(1))

        val second = resumed.change(ProcessCommand.Wait(WaitReason.Human, answer), seconds = 5)
        assertEquals(2, assertIs<ProcessPhase.Waiting>(second.phase).wait.number)
        second.expectUnchanged(ProcessCommand.Resume(1))

        val done = second.change(ProcessCommand.Resume(2), seconds = 6)
        assertEquals(ProcessPhase.Idle, done.phase)
        assertEquals(3, done.nextWait)
    }

    @Test
    fun `replaying a finish with the same outcome changes nothing`() {
        val process = newProcess().attempt(planStage, succeeded("plan-1"))
        process.expectUnchanged(ProcessCommand.Finish(1, succeeded("plan-1")))
    }

    @Test
    fun `a closed attempt cannot be closed again differently`() {
        val process = newProcess().attempt(planStage, succeeded("plan-1"))
        process.expectRejected(ProcessCommand.Finish(1, succeeded("plan-2")), ProcessRejection.OutcomeConflict)
        process.expectRejected(ProcessCommand.Finish(1, failed()), ProcessRejection.OutcomeConflict)
    }

    @Test
    fun `finishing an attempt that never ran is rejected`() {
        val running = newProcess().change(ProcessCommand.Start(planStage, helperExecutor))
        running.expectRejected(ProcessCommand.Finish(2, succeeded("x")), ProcessRejection.UnknownAttempt)
        newProcess().expectRejected(ProcessCommand.Finish(1, succeeded("x")), ProcessRejection.UnknownAttempt)
    }

    @Test
    fun `a failed attempt leaves no result and its number is never reused`() {
        val process = newProcess()
            .attempt(planStage, failed(AttemptFailure.Timeout))
            .attempt(planStage, succeeded("plan-2"), seconds = 10)

        assertEquals(listOf(1, 2), process.history.map { it.attempt.number })
        assertEquals(3, process.nextAttempt)
        assertEquals(evidence("plan-2"), process.currentResult(planStage))
        assertNull(newProcess().attempt(planStage, failed()).currentResult(planStage))
    }

    @Test
    fun `a new subject makes a result bound to the old one stale`() {
        val verified = newProcess()
            .change(ProcessCommand.ObserveSubject(sha('a')))
            .attempt(verifyStage, succeeded("check-1"))
        assertEquals(evidence("check-1"), verified.currentResult(verifyStage))

        val pushed = verified.change(ProcessCommand.ObserveSubject(sha('b')))
        assertNull(pushed.currentResult(verifyStage))
        assertEquals(evidence("check-1").id, pushed.latestSuccess(verifyStage)?.let { succeededResult(it).id })
    }

    @Test
    fun `a result bound to no subject survives the appearance of one`() {
        val planned = newProcess().attempt(planStage, succeeded("plan-1"))
        val opened = planned.change(ProcessCommand.ObserveSubject(sha('a')))
        assertEquals(evidence("plan-1"), opened.currentResult(planStage))
    }

    @Test
    fun `a result is current again only through a new attempt after the subject moved`() {
        val rerun = newProcess()
            .change(ProcessCommand.ObserveSubject(sha('a')))
            .attempt(verifyStage, succeeded("check-1"))
            .change(ProcessCommand.ObserveSubject(sha('b')))
            .attempt(verifyStage, succeeded("check-2"), seconds = 10)
        assertEquals(evidence("check-2"), rerun.currentResult(verifyStage))
    }

    @Test
    fun `a new input revision makes results of older revisions stale`() {
        val planned = newProcess().change(ProcessCommand.ReviseInput(1)).attempt(planStage, succeeded("plan-1"))
        assertEquals(evidence("plan-1"), planned.currentResult(planStage))

        val edited = planned.change(ProcessCommand.ReviseInput(2))
        assertNull(edited.currentResult(planStage))
        assertTrue(planned.isCurrent(planned.history.single()))
        assertFalse(edited.isCurrent(edited.history.single()))
    }

    @Test
    fun `input revisions only grow and replays change nothing`() {
        val process = newProcess().change(ProcessCommand.ReviseInput(5))
        process.expectUnchanged(ProcessCommand.ReviseInput(5))
        process.expectRejected(ProcessCommand.ReviseInput(4), ProcessRejection.StaleRevision)
        process.change(ProcessCommand.ObserveSubject(sha('a'))).expectUnchanged(ProcessCommand.ObserveSubject(sha('a')))
    }

    @Test
    fun `a late success for outdated inputs is recorded but not current`() {
        val running = newProcess()
            .change(ProcessCommand.ObserveSubject(sha('a')))
            .change(ProcessCommand.Start(verifyStage, helperExecutor))
        val moved = running.change(ProcessCommand.ObserveSubject(sha('b')))
        assertFalse(moved.isCurrent(assertIs<ProcessPhase.Running>(moved.phase).attempt))

        val finished = moved.change(ProcessCommand.Finish(1, succeeded("check-1")))
        assertEquals(1, finished.history.size)
        assertNull(finished.currentResult(verifyStage))
        assertEquals(sha('a'), finished.history.single().attempt.subject)
    }

    @Test
    fun `closing while an attempt runs abandons it`() {
        val running = newProcess().change(ProcessCommand.Start(planStage, helperExecutor))
        val ended = running.change(ProcessCommand.Close(ProcessEnd.Cancelled), seconds = 9)

        assertEquals(ProcessPhase.Ended(ProcessEnd.Cancelled, processAt(9)), ended.phase)
        assertEquals(AttemptOutcome.Failed(AttemptFailure.Cancelled), ended.history.single().outcome)
        assertNull(ended.inFlight)
    }

    @Test
    fun `an ended process refuses everything except exact replays`() {
        val ended = newProcess().attempt(
            planStage,
            succeeded("plan-1"),
        ).change(ProcessCommand.Close(ProcessEnd.Completed))

        ended.expectRejected(ProcessCommand.Start(buildStage, helperExecutor), ProcessRejection.Closed)
        ended.expectRejected(ProcessCommand.Wait(WaitReason.Timer, answer), ProcessRejection.Closed)
        ended.expectRejected(ProcessCommand.Resume(1), ProcessRejection.Closed)
        ended.expectRejected(ProcessCommand.ReviseInput(9), ProcessRejection.Closed)
        ended.expectRejected(ProcessCommand.ObserveSubject(sha('a')), ProcessRejection.Closed)
        ended.expectRejected(ProcessCommand.Close(ProcessEnd.Failed), ProcessRejection.Closed)
        ended.expectUnchanged(ProcessCommand.Close(ProcessEnd.Completed))
        ended.expectUnchanged(ProcessCommand.Finish(1, succeeded("plan-1")))
        ended.expectRejected(ProcessCommand.Finish(1, failed()), ProcessRejection.OutcomeConflict)
    }

    @Test
    fun `a waiting process can be closed`() {
        val ended = newProcess().change(ProcessCommand.Wait(WaitReason.Human, answer))
            .change(ProcessCommand.Close(ProcessEnd.Failed))
        assertEquals(ProcessEnd.Failed, assertIs<ProcessPhase.Ended>(ended.phase).end)
    }

    @Test
    fun `a clock that goes backwards is clamped`() {
        val process = newProcess().change(ProcessCommand.ObserveSubject(sha('a')), seconds = 50)
        val started = process.change(ProcessCommand.Start(planStage, helperExecutor), seconds = 10)
        assertEquals(processAt(50), started.updatedAt)
        assertEquals(processAt(50), started.inFlight?.startedAt)
    }

    @Test
    fun `history keeps the evidence of every stage when it overflows`() {
        var process = newProcess().attempt(planStage, succeeded("plan-1"))
        repeat(ProcessLimits.HISTORY * 2) { n ->
            process = process.attempt(buildStage, failed(), seconds = 10L * (n + 1))
        }

        assertEquals(ProcessLimits.HISTORY, process.history.size)
        assertEquals(evidence("plan-1"), process.currentResult(planStage))
        assertEquals(buildStage, process.stage)
        assertEquals(1, process.history.first().attempt.number)
        assertEquals(ProcessLimits.HISTORY * 2 + 1, process.history.last().attempt.number)
        assertEquals(process.history.map { it.attempt.number }.sorted(), process.history.map { it.attempt.number })
        assertEquals(ProcessLimits.HISTORY * 2 + 2, process.nextAttempt)
    }

    @Test
    fun `history drops the oldest superseded records first`() {
        var process = newProcess()
        repeat(ProcessLimits.HISTORY + 3) { n ->
            process = process.attempt(planStage, succeeded("plan-$n"), seconds = 10L * (n + 1))
        }
        val numbers = process.history.map { it.attempt.number }
        assertEquals(ProcessLimits.HISTORY, numbers.size)
        assertEquals(4, numbers.first())
        assertEquals(ProcessLimits.HISTORY + 3, numbers.last())
        assertEquals(evidence("plan-${ProcessLimits.HISTORY + 2}"), process.currentResult(planStage))
    }

    @Test
    fun `a process may use only so many stages`() {
        var process = newProcess()
        repeat(ProcessLimits.STAGES) { n ->
            process = process.attempt(StageName("s$n"), succeeded("r$n"), seconds = 10L * (n + 1))
        }
        process.expectRejected(ProcessCommand.Start(StageName("extra"), helperExecutor), ProcessRejection.TooManyStages)
        process.change(ProcessCommand.Start(StageName("s0"), helperExecutor))
    }

    @Test
    fun `reduce never mutates the receiver`() {
        val process = newProcess()
        process.change(ProcessCommand.Start(planStage, helperExecutor))
        assertEquals(newProcess(), process)
    }

    private fun succeededResult(record: AttemptRecord): ResultRef = assertIs<AttemptOutcome.Succeeded>(
        record.outcome,
    ).result
}
