package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessEnd
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.api.process.WritePhase
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HostWriteConfirmation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostWriteConfirmationTest {
    private val process = ProcessId("work")
    private val key = WriteKey("create")
    private val target = ResultRef("github_operation", "original")
    private val result = ResultRef("github_issue", "42")

    @Test
    fun `old receipt cannot settle a reused journal key after eviction`() = runTest {
        val env = ProcessesTestEnv(this)
        val record = env.processes.open(process)
        env.processes.apply(process, ProcessCommand.Grant(ProcessAuthority.Write))
        env.processes.apply(process, ProcessCommand.BeginWrite(key, ProcessAuthority.Write, target))
        val confirmation = receipt(record.incarnation)
        assertIs<ProcessReduction.Changed>(env.processes.confirmHostWrite(confirmation))
        repeat(ProcessLimits.WRITES) { index ->
            val next = WriteKey("next_$index")
            env.clock.seconds++
            env.processes.apply(process, ProcessCommand.BeginWrite(next, ProcessAuthority.Write, target))
            env.processes.apply(process, ProcessCommand.ConfirmWrite(next, result))
        }
        assertTrue(env.processes.get(process)!!.writes.none { it.key == key })
        env.processes.apply(
            process,
            ProcessCommand.BeginWrite(key, ProcessAuthority.Write, ResultRef("other_service", "pending")),
        )
        val before = env.processes.get(process)
        assertNull(env.processes.confirmHostWrite(confirmation))
        assertEquals(before, env.processes.get(process))
        assertEquals(WritePhase.Intended, before!!.writes.single { it.key == key }.phase)
    }

    @Test
    fun `old receipt cannot settle a reopened process with the same key and target`() = runTest {
        val env = ProcessesTestEnv(this)
        val first = env.processes.open(process)
        env.processes.apply(process, ProcessCommand.Close(ProcessEnd.Cancelled))
        assertTrue(env.processes.remove(process))
        val next = env.processes.open(process)
        assertTrue(first.incarnation != next.incarnation)
        env.processes.apply(process, ProcessCommand.Grant(ProcessAuthority.Write))
        env.processes.apply(process, ProcessCommand.BeginWrite(key, ProcessAuthority.Write, target))
        val before = env.processes.get(process)
        assertNull(env.processes.confirmHostWrite(receipt(first.incarnation)))
        assertEquals(before, env.processes.get(process))
    }

    @Test
    fun `receipt for a different authority cannot settle the same target`() = runTest {
        val env = ProcessesTestEnv(this)
        val record = env.processes.open(process)
        env.processes.apply(process, ProcessCommand.Grant(ProcessAuthority.Merge))
        env.processes.apply(process, ProcessCommand.BeginWrite(key, ProcessAuthority.Merge, target))
        val before = env.processes.get(process)
        assertNull(env.processes.confirmHostWrite(receipt(record.incarnation)))
        assertEquals(before, env.processes.get(process))
    }

    @Test
    fun `matching unknown write confirms once and identical receipt replays`() = runTest {
        val env = ProcessesTestEnv(this)
        val record = env.processes.open(process)
        env.processes.apply(process, ProcessCommand.Grant(ProcessAuthority.Write))
        env.processes.apply(process, ProcessCommand.BeginWrite(key, ProcessAuthority.Write, target))
        val tries = env.processes.get(process)!!.writes.single().tries
        env.processes.apply(process, ProcessCommand.MarkWriteUnknown(key, tries))
        val confirmation = receipt(record.incarnation)
        assertIs<ProcessReduction.Changed>(env.processes.confirmHostWrite(confirmation))
        assertEquals(WritePhase.Confirmed(result), env.processes.get(process)!!.writes.single().phase)
        assertIs<ProcessReduction.Unchanged>(env.processes.confirmHostWrite(confirmation))
        assertNull(env.processes.confirmHostWrite(confirmation.copy(result = ResultRef("github_issue", "43"))))
    }

    private fun receipt(incarnation: String) = HostWriteConfirmation(
        process,
        incarnation,
        key,
        target,
        ProcessAuthority.Write,
        result,
    )
}
