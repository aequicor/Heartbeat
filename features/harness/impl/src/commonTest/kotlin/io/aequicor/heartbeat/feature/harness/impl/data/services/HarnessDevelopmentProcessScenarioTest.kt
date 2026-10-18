package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.api.process.AttemptFailure
import io.aequicor.heartbeat.feature.harness.api.process.AttemptOutcome
import io.aequicor.heartbeat.feature.harness.api.process.AttemptRecord
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorKind
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorRef
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessEnd
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecovery
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRejection
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WaitReason
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.api.process.WritePhase
import io.aequicor.heartbeat.feature.harness.api.process.recovery
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The life of one development process across restarts: issue, plan, execution by an executor the process does not
 * hold, a draft pull request, verification against a head, a wait for a person. Executors and the hosting service
 * are fakes; the process records and the real service over protected storage are what is under test. A restart is a
 * second [ProcessesTestEnv] over the same stored bytes.
 */
class HarnessDevelopmentProcessScenarioTest {
    private val issue = ProcessId("issue_7")
    private val plan = StageName("plan")
    private val build = StageName("build")
    private val verify = StageName("verify")
    private val planner = ExecutorRef(ExecutorKind.Helper, "planner-1")
    private val cell = ExecutorRef(ExecutorKind.Organism, "cell-1")
    private val checker = ExecutorRef(ExecutorKind.Command, "check-1")
    private val headOne = SubjectSha("1".repeat(40))
    private val headTwo = SubjectSha("2".repeat(40))
    private val answer = WakeCondition(events = setOf(EventKeys.custom("process.answer")))
    private val draftKey = WriteKey("draft_pr_issue_7")

    @Test
    fun `a restart in the middle of execution loses neither the stage nor its evidence`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val first = ProcessesTestEnv(this, secrets)
        first.processes.open(issue, inputRevision = 1)
        first.start(plan, planner)
        first.finish(1, AttemptOutcome.Succeeded(ResultRef("report", "plan-1")))
        first.start(build, cell)

        val second = ProcessesTestEnv(this, secrets, restartedAt = 600)
        val restored = checkNotNull(second.processes.get(issue))
        assertEquals(build, restored.stage)
        assertEquals(ResultRef("report", "plan-1"), restored.currentResult(plan))
        val open = restored.recovery(second.clock.now()).single()
        assertEquals(cell, assertIs<ProcessRecovery.AttemptOpen>(open).attempt.executor)

        // The cell's owner reports that it finished and pushed a head; the process takes that as the outcome.
        second.finish(2, AttemptOutcome.Succeeded(ResultRef("branch", "heartbeat-issue-7")))
        second.send(ProcessCommand.ObserveSubject(headOne))
        val settled = checkNotNull(second.processes.get(issue))
        assertTrue(settled.recovery(second.clock.now()).isEmpty())
        assertEquals(headOne, settled.subject)
    }

    @Test
    fun `an executor that cannot be accounted for after a restart is closed as interrupted`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val first = ProcessesTestEnv(this, secrets)
        first.processes.open(issue)
        first.start(build, cell)

        val second = ProcessesTestEnv(this, secrets)
        // The restart does not release the executor by itself: the attempt stays in flight until it is closed.
        second.send(ProcessCommand.Start(build, cell)).assertRejected(ProcessRejection.AttemptInFlight)
        second.finish(1, AttemptOutcome.Failed(AttemptFailure.Interrupted))
        val record = checkNotNull(second.processes.get(issue))
        assertEquals(ProcessPhase.Idle, record.phase)
        assertNull(record.currentResult(build))
        assertEquals(AttemptOutcome.Failed(AttemptFailure.Interrupted), record.history.single().outcome)
        second.send(ProcessCommand.Finish(1, AttemptOutcome.Succeeded(ResultRef("report", "late"))))
            .assertRejected(ProcessRejection.OutcomeConflict)
        second.start(build, cell)
        assertEquals(3, checkNotNull(second.processes.get(issue)).nextAttempt)
    }

    @Test
    fun `a crash between the intent and the external call does not lose or repeat the write`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val hosting = FakeHosting()
        val first = ProcessesTestEnv(this, secrets)
        first.processes.open(issue)
        first.grant(ProcessAuthority.Write)
        first.beginDraftPullRequest()
        // The process dies here: the intent is stored, the hosting service was never called.

        val second = ProcessesTestEnv(this, secrets)
        val recovered = checkNotNull(second.processes.get(issue))
        assertEquals(listOf(draftKey), recovered.unresolvedWrites().map { it.key })
        second.send(ProcessCommand.BeginWrite(draftKey, ProcessAuthority.Write, REPOSITORY)).assertRejected(
            ProcessRejection.WriteUnresolved,
        )

        assertIs<ProcessReduction.Changed>(second.send(ProcessCommand.MarkWriteUnknown(draftKey, 1)))
        assertNull(hosting.find(draftKey.value))
        assertIs<ProcessReduction.Changed>(second.send(ProcessCommand.WriteNotApplied(draftKey, 1)))
        second.beginDraftPullRequest()
        val created = hosting.createDraft(draftKey.value)

        // A second reconciler that looked at the first try arrives late: it cannot reset the retry that is under way.
        assertIs<ProcessReduction.Unchanged>(second.send(ProcessCommand.WriteNotApplied(draftKey, 1)))
        second.send(ProcessCommand.BeginWrite(draftKey, ProcessAuthority.Write, REPOSITORY)).assertRejected(
            ProcessRejection.WriteUnresolved,
        )
        second.send(ProcessCommand.ConfirmWrite(draftKey, ResultRef("pull_request", created)))

        assertEquals(1, hosting.pullRequests.size)
        val record = checkNotNull(second.processes.get(issue))
        assertEquals(2, record.write(draftKey)?.tries)
        assertEquals(WritePhase.Confirmed(ResultRef("pull_request", created)), record.write(draftKey)?.phase)
    }

    @Test
    fun `a crash after the external call but before the confirmation does not repeat the write`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val hosting = FakeHosting()
        val first = ProcessesTestEnv(this, secrets)
        first.processes.open(issue)
        first.grant(ProcessAuthority.Write)
        first.beginDraftPullRequest()
        val created = hosting.createDraft(draftKey.value)
        // The process dies here: the pull request exists, the confirmation was never stored.

        val second = ProcessesTestEnv(this, secrets)
        val open = second.processes.get(issue)?.recovery(second.clock.now()).orEmpty()
        assertEquals(draftKey, assertIs<ProcessRecovery.WriteOpen>(open.single()).write.key)

        second.send(ProcessCommand.BeginWrite(draftKey, ProcessAuthority.Write, REPOSITORY)).assertRejected(
            ProcessRejection.WriteUnresolved,
        )
        assertIs<ProcessReduction.Changed>(second.send(ProcessCommand.MarkWriteUnknown(draftKey, 1)))
        val found = checkNotNull(hosting.find(draftKey.value))
        assertEquals(created, found)
        second.send(ProcessCommand.ConfirmWrite(draftKey, ResultRef("pull_request", found)))

        // Doing the same thing again is recognized as done: the harness only calls the hosting after a Changed.
        val replay = second.send(ProcessCommand.BeginWrite(draftKey, ProcessAuthority.Write, REPOSITORY))
        if (replay is ProcessReduction.Changed) hosting.createDraft(draftKey.value)
        assertEquals(1, hosting.pullRequests.size)
        assertIs<ProcessReduction.Unchanged>(replay)
        assertEquals(1, checkNotNull(second.processes.get(issue)).write(draftKey)?.tries)
        assertTrue(checkNotNull(second.processes.get(issue)).recovery(second.clock.now()).isEmpty())
    }

    @Test
    fun `a new head makes the earlier verification stale across restarts`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val first = ProcessesTestEnv(this, secrets)
        first.processes.open(issue)
        first.send(ProcessCommand.ObserveSubject(headOne))
        first.start(verify, checker)
        first.finish(1, AttemptOutcome.Succeeded(ResultRef("report", "checks-on-one")))
        assertEquals(ResultRef("report", "checks-on-one"), first.record().currentResult(verify))

        // Somebody pushes to the branch while the process is down.
        val second = ProcessesTestEnv(this, secrets)
        // Control: the restart alone changes nothing, the subject and the result survived it.
        assertEquals(ResultRef("report", "checks-on-one"), second.record().currentResult(verify))
        assertIs<ProcessReduction.Unchanged>(second.send(ProcessCommand.ObserveSubject(headOne)))
        second.send(ProcessCommand.ObserveSubject(headTwo))
        assertNull(second.record().currentResult(verify))
        assertEquals(ResultRef("report", "checks-on-one"), second.record().latestSuccess(verify)?.let { evidence(it) })

        val third = ProcessesTestEnv(this, secrets)
        assertNull(third.record().currentResult(verify))
        third.start(verify, checker)
        assertEquals(headTwo, third.record().inFlight?.subject)
        third.finish(2, AttemptOutcome.Succeeded(ResultRef("report", "checks-on-two")))
        assertEquals(ResultRef("report", "checks-on-two"), third.record().currentResult(verify))
    }

    @Test
    fun `waiting for a person holds no executor and a duplicate answer cannot end the next wait`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val first = ProcessesTestEnv(this, secrets)
        first.processes.open(issue)
        first.start(plan, planner)
        first.finish(1, AttemptOutcome.Succeeded(ResultRef("question", "q-1")))
        assertIs<ProcessReduction.Changed>(first.send(ProcessCommand.Wait(WaitReason.Human, answer)))
        assertIs<ProcessPhase.Waiting>(first.record().phase)
        assertNull(first.record().inFlight)

        val second = ProcessesTestEnv(this, secrets, restartedAt = 3_600)
        val waiting = second.record()
        assertNull(waiting.inFlight)
        val open = waiting.recovery(second.clock.now()).single()
        assertEquals(1, assertIs<ProcessRecovery.WaitOpen>(open).wait.number)

        second.send(ProcessCommand.Resume(1))
        assertEquals(ProcessPhase.Idle, second.record().phase)
        second.send(ProcessCommand.Wait(WaitReason.Human, answer))

        // The first answer is delivered again, after the process already waits for the next one.
        val third = ProcessesTestEnv(this, secrets)
        assertIs<ProcessReduction.Unchanged>(third.send(ProcessCommand.Resume(1)))
        assertIs<ProcessPhase.Waiting>(third.record().phase)
        assertIs<ProcessReduction.Changed>(third.send(ProcessCommand.Resume(2)))
    }

    @Test
    fun `a wait with a deadline survives a restart and says when it is due`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val first = ProcessesTestEnv(this, secrets)
        first.processes.open(issue)
        val deadline = Instant.fromEpochSeconds(PROCESS_TEST_START + 500)
        val timed = WakeCondition(events = setOf(EventKeys.custom("process.answer")), deadline = deadline)
        assertIs<ProcessReduction.Changed>(first.send(ProcessCommand.Wait(WaitReason.Timer, timed)))

        val early = ProcessesTestEnv(this, secrets, restartedAt = 100)
        val notYet = assertIs<ProcessRecovery.WaitOpen>(early.record().recovery(early.clock.now()).single())
        assertEquals(false, notYet.isDue)
        val late = ProcessesTestEnv(this, secrets, restartedAt = 600)
        val due = assertIs<ProcessRecovery.WaitOpen>(late.record().recovery(late.clock.now()).single())
        assertEquals(true, due.isDue)
    }

    @Test
    fun `publishing a pull request is not merging it`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(issue)
        env.grant(ProcessAuthority.Write)
        env.grant(ProcessAuthority.Publish)
        val publish = WriteKey("ready_issue_7")
        val merge = WriteKey("merge_issue_7")

        assertIs<ProcessReduction.Changed>(
            env.send(ProcessCommand.BeginWrite(publish, ProcessAuthority.Publish, REPOSITORY)),
        )
        env.send(ProcessCommand.BeginWrite(merge, ProcessAuthority.Merge, REPOSITORY))
            .assertRejected(ProcessRejection.NotAuthorized)
        assertNull(env.record().write(merge))

        env.grant(ProcessAuthority.Merge)
        assertIs<ProcessReduction.Changed>(
            env.send(ProcessCommand.BeginWrite(merge, ProcessAuthority.Merge, REPOSITORY)),
        )
        env.send(ProcessCommand.Revoke(ProcessAuthority.Merge))
        env.send(ProcessCommand.BeginWrite(WriteKey("merge_again"), ProcessAuthority.Merge, REPOSITORY))
            .assertRejected(ProcessRejection.NotAuthorized)
    }

    @Test
    fun `a finished process is removed and stays gone after a restart`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val first = ProcessesTestEnv(this, secrets)
        first.processes.open(issue)
        first.start(plan, planner)
        first.finish(1, AttemptOutcome.Succeeded(ResultRef("report", "plan-1")))
        first.send(ProcessCommand.Close(ProcessEnd.Completed))
        assertTrue(first.processes.remove(issue))

        val second = ProcessesTestEnv(this, secrets)
        assertNull(second.processes.get(issue))
        assertTrue(second.processes.list().isEmpty())
    }

    private suspend fun ProcessesTestEnv.start(stage: StageName, executor: ExecutorRef) {
        clock.seconds += 1
        assertIs<ProcessReduction.Changed>(send(ProcessCommand.Start(stage, executor)))
    }

    private suspend fun ProcessesTestEnv.finish(attempt: Int, outcome: AttemptOutcome) {
        clock.seconds += 1
        assertIs<ProcessReduction.Changed>(send(ProcessCommand.Finish(attempt, outcome)))
    }

    private suspend fun ProcessesTestEnv.grant(authority: ProcessAuthority) {
        assertIs<ProcessReduction.Changed>(send(ProcessCommand.Grant(authority)))
    }

    private suspend fun ProcessesTestEnv.beginDraftPullRequest() {
        assertIs<ProcessReduction.Changed>(
            send(ProcessCommand.BeginWrite(draftKey, ProcessAuthority.Write, REPOSITORY)),
        )
    }

    private suspend fun ProcessesTestEnv.send(command: ProcessCommand): ProcessReduction =
        processes.apply(issue, command)

    private suspend fun ProcessesTestEnv.record(): ProcessRecord = checkNotNull(processes.get(issue))

    private fun evidence(record: AttemptRecord): ResultRef = assertIs<AttemptOutcome.Succeeded>(record.outcome).result

    private fun ProcessReduction.assertRejected(reason: ProcessRejection) {
        assertEquals(ProcessReduction.Rejected(reason), this)
    }

    /** A hosting service whose create call is not idempotent, as real ones are; the marker is how it is found again. */
    private class FakeHosting {
        val pullRequests = mutableMapOf<String, String>()

        fun createDraft(marker: String): String {
            val id = "pr-${pullRequests.size + 1}"
            pullRequests[id] = marker
            return id
        }

        fun find(marker: String): String? = pullRequests.entries.firstOrNull { it.value == marker }?.key
    }

    private companion object {
        val REPOSITORY = ResultRef("repository", "aequicor-heartbeat")
    }
}
