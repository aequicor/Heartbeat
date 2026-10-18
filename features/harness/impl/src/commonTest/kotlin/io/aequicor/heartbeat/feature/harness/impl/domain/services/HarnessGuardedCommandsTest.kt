package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommand
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandRefusal
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcessCommandResult
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestAccess
import io.aequicor.heartbeat.feature.harness.impl.data.services.RecordingLocalCommands
import io.aequicor.heartbeat.feature.harness.impl.data.services.SavedProjectScopes
import io.aequicor.heartbeat.feature.harness.impl.data.services.commandsTestFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarnessGuardedCommandsTest {
    @Test
    fun `ordinary run cannot bypass guarded declaration and absent host never permits execution`() = runTest {
        val workspace = WorkspaceRef("project")
        val local = RecordingLocalCommands()
        val baseAccess = ProcessesTestAccess(this).apply { isActive = false }
        val access = object : HarnessInstanceAccess by baseAccess {
            override val isRegistrationAllowed = true
        }
        val commands = commandsTestFactory(local, SavedProjectScopes(setOf(workspace)))
            .create(HarnessActivationRequest(harness, code, 1), access)
        val name = ItemName("merge")
        commands.define(
            name,
            ScriptCommand("gh", listOf("pr", "merge", "1"), requiresDecision = ProcessAuthority.Merge),
        )
        commands.seal()
        baseAccess.isActive = true
        assertEquals(ScriptCommandResult.Refused(ScriptCommandRefusal.DecisionRequired), commands.run(name, workspace))
        assertEquals(
            ScriptProcessCommandResult.Unavailable,
            commands.runForProcess(
                name,
                workspace,
                ProcessWorkflowKey(ProcessId("process"), "life", 1, StageName("merge")),
            ),
        )
        assertTrue(local.requests.isEmpty())
    }
}
