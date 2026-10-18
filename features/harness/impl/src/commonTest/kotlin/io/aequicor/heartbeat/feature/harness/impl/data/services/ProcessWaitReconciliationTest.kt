package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRejection
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WaitReason
import io.aequicor.heartbeat.feature.harness.api.script.reconcileWait
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProcessWaitReconciliationTest {
    private val id = ProcessId("issue_7")
    private val condition = WakeCondition(events = setOf(EventKeys.custom("process.answer")))

    @Test
    fun `activation recovers a missed event after a week without retaining an executor`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val before = ProcessesTestEnv(this, secrets)
        before.processes.open(id)
        before.processes.apply(id, ProcessCommand.Wait(WaitReason.External, condition))
        before.access.isActive = false

        val after = ProcessesTestEnv(this, secrets, restartedAt = 7 * 24 * 3600)
        assertIs<ProcessReduction.Unchanged>(after.processes.reconcileWait(id) { _, _ -> false })
        val resumed = assertIs<ProcessReduction.Changed>(
            after.processes.reconcileWait(id) { record, wait ->
                assertNull(record.inFlight)
                assertEquals(condition, wait.condition)
                true
            },
        ).record
        assertEquals(ProcessPhase.Idle, resumed.phase)
        assertEquals(1, resumed.nextAttempt)
        assertEquals(resumed, after.processes.get(id))
    }

    @Test
    fun `a concurrent input update rejects the external answer without deleting the wait`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(id)
        env.processes.apply(id, ProcessCommand.Wait(WaitReason.External, condition))
        val result = env.processes.reconcileWait(id) { _, _ ->
            env.processes.apply(id, ProcessCommand.ReviseInput(2))
            true
        }
        assertEquals(ProcessReduction.Rejected(ProcessRejection.StaleRevision), result)
        assertIs<ProcessPhase.Waiting>(requireNotNull(env.processes.get(id)).phase)
    }

    @Test
    fun `a concurrent SHA update rejects the external answer`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(id)
        env.processes.apply(id, ProcessCommand.Wait(WaitReason.External, condition))
        val result = env.processes.reconcileWait(id) { _, _ ->
            env.processes.apply(id, ProcessCommand.ObserveSubject(SubjectSha("a".repeat(40))))
            true
        }
        assertEquals(ProcessReduction.Rejected(ProcessRejection.StaleRevision), result)
    }
}
