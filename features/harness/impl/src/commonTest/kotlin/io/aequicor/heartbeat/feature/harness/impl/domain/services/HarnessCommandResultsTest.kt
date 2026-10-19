package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandReceipt
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommand
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcessCommandResult
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandOrigins
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandToggles
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessCommandSlots
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestAccess
import io.aequicor.heartbeat.feature.harness.impl.data.services.RecordingLocalCommands
import io.aequicor.heartbeat.feature.harness.impl.data.services.SavedProjectScopes
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.HarnessProcessCommandHost
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessCommandInvocation
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HarnessCommandResultsTest {
    @Test
    fun `reading a receipt needs scope and active declaration but no command runner or execution slot`() = runTest {
        val workspace = WorkspaceRef("project")
        val local = RecordingLocalCommands(isAvailable = false)
        val slots = HarnessCommandSlots()
        repeat(HarnessLimits.COMMAND_RUNS) { assertTrue(slots.tryAcquire(harness.id)) }
        val toggles = CommandToggles()
        val baseAccess = ProcessesTestAccess(this).apply { isActive = false }
        val access = object : HarnessInstanceAccess by baseAccess {
            override val isRegistrationAllowed = true
        }
        val reads = mutableListOf<Pair<ProcessCommandInvocation, String>>()
        val expected = ScriptProcessCommandResult.Recorded(ProcessCommandReceipt.Exited(17))
        val executor = object : HarnessProcessCommandHost {
            override suspend fun execute(invocation: ProcessCommandInvocation): ScriptProcessCommandResult =
                error("A result read cannot execute")

            override suspend fun read(
                invocation: ProcessCommandInvocation,
                decisionId: String,
            ): ScriptProcessCommandResult {
                reads += invocation to decisionId
                return expected
            }
        }
        val commands = HarnessScriptCommands(
            HarnessActivationRequest(harness, code, 1),
            access,
            HarnessCommandHost(local, SavedProjectScopes(setOf(workspace)), slots, CommandOrigins(), executor) {
                toggles.isEnabled
            },
        )
        val name = ItemName("publish")
        commands.define(name, ScriptCommand("publisher", requiresDecision = ProcessAuthority.Publish))
        commands.define(ItemName("ordinary"), ScriptCommand("publisher"))
        commands.seal()
        baseAccess.isActive = true
        val key = ProcessWorkflowKey(ProcessId("process"), "life", 1, StageName("publish"))
        assertEquals(expected, commands.resultForProcess(name, workspace, key, "decision"))
        assertEquals("decision", reads.single().second)
        for (invalid in listOf(ItemName("unknown"), ItemName("ordinary"))) {
            assertEquals(
                ScriptProcessCommandResult.Unavailable,
                commands.resultForProcess(invalid, workspace, key, "decision"),
            )
        }
        assertEquals(
            ScriptProcessCommandResult.Unavailable,
            commands.resultForProcess(name, WorkspaceRef("unknown"), key, "decision"),
        )
        toggles.isEnabled = false
        assertEquals(
            ScriptProcessCommandResult.Unavailable,
            commands.resultForProcess(name, workspace, key, "decision"),
        )
        baseAccess.isActive = false
        assertFailsWith<IllegalStateException> { commands.resultForProcess(name, workspace, key, "decision") }
        assertEquals(1, reads.size)
        assertTrue(local.requests.isEmpty())
    }
}
