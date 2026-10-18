package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRejection
import io.aequicor.heartbeat.feature.harness.api.process.WaitReason
import io.aequicor.heartbeat.feature.harness.api.process.waitKey
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProcessDefinitionMigrationTest {
    private val id = ProcessId("task")
    private val first = ProcessDefinition(ItemId("script"), 1)
    private val next = first.copy(revision = 2)
    private val condition = WakeCondition(events = setOf(EventKeys.custom("process.answer")))

    @Test
    fun `a new activation cannot resume pinned logic until explicit migration`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val old = ProcessesTestEnv(this, secrets, definition = first)
        assertEquals(first, old.processes.open(id).definition)
        old.processes.apply(id, ProcessCommand.Wait(WaitReason.External, condition))
        val snapshot = requireNotNull(old.processes.get(id))
        val oldKey = requireNotNull(snapshot.waitKey())
        old.access.isActive = false

        val fresh = ProcessesTestEnv(this, secrets, definition = next)
        assertEquals(
            ProcessReduction.Rejected(ProcessRejection.DefinitionMismatch),
            fresh.processes.apply(id, ProcessCommand.ResumeChecked(oldKey)),
        )
        assertEquals(snapshot, fresh.processes.get(id))
        assertIs<ProcessReduction.Changed>(
            fresh.processes.apply(id, ProcessCommand.MigrateDefinition(first, next)),
        )
        // Even after acceptance, an observation captured by the old program cannot continue the new one.
        assertEquals(
            ProcessReduction.Rejected(ProcessRejection.DefinitionMismatch),
            fresh.processes.apply(id, ProcessCommand.ResumeChecked(oldKey)),
        )
        val key = requireNotNull(requireNotNull(fresh.processes.get(id)).waitKey())
        val resumed = assertIs<ProcessReduction.Changed>(
            fresh.processes.apply(id, ProcessCommand.ResumeChecked(key)),
        ).record
        assertEquals(ProcessPhase.Idle, resumed.phase)
    }

    @Test
    fun `legacy data requires acceptance and cannot pin a different activation`() = runTest {
        val secrets = HarnessDataTestSecrets()
        ProcessesTestEnv(this, secrets).processes.open(id)
        val fresh = ProcessesTestEnv(this, secrets, definition = next)
        assertEquals(
            ProcessReduction.Rejected(ProcessRejection.DefinitionMismatch),
            fresh.processes.apply(id, ProcessCommand.MigrateDefinition(null, first)),
        )
        assertIs<ProcessReduction.Changed>(
            fresh.processes.apply(id, ProcessCommand.MigrateDefinition(null, next)),
        )
        assertEquals(next, fresh.processes.get(id)?.definition)
    }

    @Test
    fun `disabled flag preserves legacy behavior but cannot bypass an existing pin`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val legacy = ProcessesTestEnv(this, secrets, definition = first, isVersioningEnabled = { false })
        assertEquals(null, legacy.processes.open(id).definition)
        assertIs<ProcessReduction.Changed>(legacy.processes.apply(id, ProcessCommand.ReviseInput(1)))

        val enabled = ProcessesTestEnv(this, secrets, definition = first)
        enabled.processes.apply(id, ProcessCommand.MigrateDefinition(null, first))
        val disabledNewCode = ProcessesTestEnv(this, secrets, definition = next, isVersioningEnabled = { false })
        assertEquals(
            ProcessReduction.Rejected(ProcessRejection.DefinitionMismatch),
            disabledNewCode.processes.apply(id, ProcessCommand.ReviseInput(2)),
        )
        assertEquals(first, disabledNewCode.processes.get(id)?.definition)
    }
}
