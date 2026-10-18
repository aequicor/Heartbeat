package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class ProcessWorkflowTest {
    private val now = Instant.fromEpochSeconds(1000)
    private val initial = ProcessRecord(ProcessId("task"), now, incarnation = "lifetime")
    private val key = initial.workflowKey(StageName("verify"))
    private val claim = ProcessWorkflow(RunId("wf_first"), ItemId("script"), "a".repeat(64))

    @Test
    fun `reservation binds the attempt before dispatch and replays its first identity`() {
        val stored = reserve()
        assertEquals(ExecutorRef(ExecutorKind.Workflow, claim.run.value), stored.inFlight?.executor)
        assertEquals(claim, stored.inFlight?.workflow)
        assertEquals(key, stored.workflowKey(key.stage))
        val retry = ProcessCommand.StartWorkflow(key, claim.copy(run = RunId("wf_other")))
        assertEquals(ProcessReduction.Unchanged(stored), stored.reduce(retry, now))
        assertEquals(stored, Json.decodeFromString<ProcessRecord>(Json.encodeToString(stored)))
    }

    @Test
    fun `changed admission and another script conflict with an existing reservation`() {
        val stored = reserve()
        for (other in listOf(claim.copy(digest = "b".repeat(64)), claim.copy(script = ItemId("other")))) {
            assertEquals(
                ProcessReduction.Rejected(ProcessRejection.OutcomeConflict),
                stored.reduce(ProcessCommand.StartWorkflow(key, other), now),
            )
        }
    }

    @Test
    fun `late replay of a closed attempt cannot start or finish a newer attempt`() {
        val stored = reserve()
        val finished = assertIs<ProcessReduction.Changed>(
            stored.reduce(ProcessCommand.Finish(1, AttemptOutcome.Failed(AttemptFailure.Error)), now),
        ).record
        val newer = assertIs<ProcessReduction.Changed>(
            finished.reduce(
                ProcessCommand.Start(StageName("implement"), ExecutorRef(ExecutorKind.External, "next")),
                now,
            ),
        ).record
        assertEquals(ProcessReduction.Unchanged(newer), newer.reduce(ProcessCommand.StartWorkflow(key, claim), now))
        assertEquals(2, newer.inFlight?.number)
    }

    @Test
    fun `another lifetime and an unissued attempt cannot reserve execution`() {
        for (other in listOf(key.copy(incarnation = "old"), key.copy(attempt = 2))) {
            assertEquals(
                ProcessReduction.Rejected(ProcessRejection.UnknownAttempt),
                initial.reduce(ProcessCommand.StartWorkflow(other, claim), now),
            )
        }
    }

    @Test
    fun `old cancellation cannot close a reopened process or a different reservation`() {
        val stored = reserve()
        val reopened = stored.copy(incarnation = "new_lifetime")
        assertEquals(
            ProcessReduction.Rejected(ProcessRejection.UnknownAttempt),
            reopened.reduce(ProcessCommand.CancelWorkflow(key, claim), now),
        )
        assertEquals(
            ProcessReduction.Rejected(ProcessRejection.OutcomeConflict),
            stored.reduce(ProcessCommand.CancelWorkflow(key, claim.copy(run = RunId("wf_other"))), now),
        )
        val cancelled = assertIs<ProcessReduction.Changed>(
            stored.reduce(ProcessCommand.CancelWorkflow(key, claim), now),
        ).record
        assertEquals(null, cancelled.inFlight)
        assertEquals(
            ProcessReduction.Unchanged(cancelled),
            cancelled.reduce(ProcessCommand.CancelWorkflow(key, claim), now),
        )
    }

    @Test
    fun `a new workflow stage cannot silently adopt a different approved revision`() {
        val definition = ProcessDefinition(claim.script, 1)
        val command = ProcessCommand.StartWorkflow(key, claim, definition = definition)
        initial.expectRejected(command, ProcessRejection.DefinitionMismatch)
        val accepted = initial.change(ProcessCommand.MigrateDefinition(null, definition))
        val reserved = assertIs<ProcessReduction.Changed>(accepted.reduce(command, now)).record
        assertEquals(definition, reserved.definition)
        val closed = assertIs<ProcessReduction.Changed>(
            reserved.reduce(ProcessCommand.Finish(1, AttemptOutcome.Succeeded(ResultRef("report", "result"))), now),
        ).record
        val nextKey = closed.workflowKey(key.stage)
        val nextDefinition = definition.copy(revision = 2)
        assertEquals(
            ProcessReduction.Rejected(ProcessRejection.DefinitionMismatch),
            closed.reduce(ProcessCommand.StartWorkflow(nextKey, claim, definition = nextDefinition), now),
        )
        val migrated = assertIs<ProcessReduction.Changed>(
            closed.reduce(ProcessCommand.MigrateDefinition(definition, nextDefinition), now),
        ).record
        assertIs<ProcessReduction.Changed>(
            migrated.reduce(ProcessCommand.StartWorkflow(nextKey, claim, definition = nextDefinition), now),
        )
    }

    private fun reserve(): ProcessRecord = assertIs<ProcessReduction.Changed>(
        initial.reduce(ProcessCommand.StartWorkflow(key, claim), now),
    ).record
}
