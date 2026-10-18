package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProcessWaitKeyTest {
    private val condition = WakeCondition(events = setOf(EventKeys.custom("process.answer")))

    private fun waiting(): ProcessRecord = newProcess()
        .change(ProcessCommand.ObserveSubject(sha('a')))
        .change(ProcessCommand.Wait(WaitReason.External, condition))

    @Test
    fun `a restored wait resumes days later without a workflow`() {
        val stored = waiting()
        val restored = Json.decodeFromString<ProcessRecord>(Json.encodeToString(stored))
        val key = requireNotNull(restored.waitKey())
        val resumed = restored.change(ProcessCommand.ResumeChecked(key), seconds = 7 * 24 * 3600)
        assertEquals(ProcessPhase.Idle, resumed.phase)
        assertNull(resumed.inFlight)
        assertEquals(1, resumed.nextAttempt)
        resumed.expectUnchanged(ProcessCommand.ResumeChecked(key))
    }

    @Test
    fun `a revision or SHA changed during reconciliation refuses the observation`() {
        val record = waiting()
        val command = ProcessCommand.ResumeChecked(requireNotNull(record.waitKey()))
        record.change(ProcessCommand.ReviseInput(1)).expectRejected(command, ProcessRejection.StaleRevision)
        record.change(ProcessCommand.ObserveSubject(sha('b'))).expectRejected(command, ProcessRejection.StaleRevision)
    }

    @Test
    fun `a late answer preserves an ended process or a new running attempt`() {
        val record = waiting()
        val command = ProcessCommand.ResumeChecked(requireNotNull(record.waitKey()))
        record.change(ProcessCommand.Close(ProcessEnd.Cancelled)).expectUnchanged(command)
        val running = record.change(command).change(ProcessCommand.Start(buildStage, helperExecutor))
        running.expectUnchanged(command)
        assertIs<ProcessPhase.Running>(running.phase)
    }

    @Test
    fun `a delayed observation cannot resume a replacement wait or incarnation`() {
        val record = waiting()
        val key = requireNotNull(record.waitKey())
        val next = record.change(ProcessCommand.ResumeChecked(key))
            .change(ProcessCommand.Wait(WaitReason.Human, condition))
        next.expectUnchanged(ProcessCommand.ResumeChecked(key))
        assertIs<ProcessPhase.Waiting>(next.phase)
        record.copy(incarnation = "replacement").expectRejected(
            ProcessCommand.ResumeChecked(key),
            ProcessRejection.UnknownWait,
        )
        record.expectRejected(ProcessCommand.ResumeChecked(key.copy(wait = 99)), ProcessRejection.UnknownWait)
        record.expectRejected(
            ProcessCommand.ResumeChecked(key.copy(process = ProcessId("other"))),
            ProcessRejection.UnknownWait,
        )
    }
}
