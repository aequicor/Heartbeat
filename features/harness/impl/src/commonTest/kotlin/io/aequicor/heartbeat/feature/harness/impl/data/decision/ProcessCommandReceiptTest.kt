package io.aequicor.heartbeat.feature.harness.impl.data.decision

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandReceipt
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionCommand
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandRefusal
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcessCommandResult
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessCommandReceiptTest {
    @Test
    fun `saved exit code survives lost caller result and changed HEAD without running Git`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        val call = env.invocation()
        val pending = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(call))
        env.approve()
        env.local.outcome = LocalCommandOutcome.Exited(17, "private-output")
        assertIs<ScriptProcessCommandResult.Finished>(env.executor().execute(call))
        assertFalse(Json.encodeToString(env.host.ready()).contains("private-output"))
        env.restart()
        env.local.head = "b".repeat(40)
        env.local.afterRemoteRead = { error("Reading a receipt must not inspect Git") }
        repeat(2) {
            assertEquals(
                ScriptProcessCommandResult.Recorded(ProcessCommandReceipt.Exited(17)),
                env.executor().read(call, pending.id),
            )
        }
        assertEquals(1, env.local.executions.size)
    }

    @Test
    fun `read never creates a decision or consumes approval`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        val call = env.invocation()
        assertEquals(ScriptProcessCommandResult.Unavailable, env.executor().read(call, "missing"))
        assertTrue(env.host.ready()!!.entries.isEmpty())
        val pending = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(call))
        assertEquals(pending, env.executor().read(call, pending.id))
        env.approve()
        val approved = assertIs<ScriptProcessCommandResult.Decision>(env.executor().read(call, pending.id))
        assertEquals(ProcessDecisionPhase.Approved, approved.phase)
        assertTrue(env.local.executions.isEmpty())
    }

    @Test
    fun `native refusal is retained and never interpreted as success`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        val call = env.invocation()
        val pending = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(call))
        env.approve()
        env.local.outcome = LocalCommandOutcome.Unsupported
        assertIs<ScriptProcessCommandResult.Finished>(env.executor().execute(call))
        env.restart()
        assertEquals(
            ScriptProcessCommandResult.Recorded(ProcessCommandReceipt.Refused(ScriptCommandRefusal.Unsupported)),
            env.executor().read(call, pending.id),
        )
        assertEquals(1, env.local.executions.size)
    }

    @Test
    fun `legacy completion and timeout expose no inferred result`() = runTest {
        for (legacy in listOf(true, false)) {
            val env = ProcessCommandTestEnv(this)
            env.initialize()
            val call = env.invocation()
            val pending = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(call))
            env.approve()
            if (legacy) {
                val entry = env.host.ready()!!.find(pending.id)!!
                val claim = ProcessDecisionCommand.Claim(entry.decision.binding)
                assertTrue(env.host.edit(ProcessDecisionEdit.Change(pending.id, claim)))
                assertTrue(env.host.edit(ProcessDecisionEdit.Change(pending.id, ProcessDecisionCommand.Complete)))
            } else {
                env.local.outcome = LocalCommandOutcome.TimedOut("private-output")
                assertIs<ScriptProcessCommandResult.Finished>(env.executor().execute(call))
            }
            env.restart()
            val result = assertIs<ScriptProcessCommandResult.Decision>(env.executor().read(call, pending.id))
            assertEquals(if (legacy) ProcessDecisionPhase.Completed else ProcessDecisionPhase.Executing, result.phase)
        }
    }

    @Test
    fun `receipt cannot be read through another owner source workspace or command`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        val call = env.invocation()
        val pending = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(call))
        env.approve()
        env.executor().execute(call)
        val changes = listOf(
            call.copy(
                activation = call.activation.copy(harness = call.activation.harness.copy(id = HarnessId("other"))),
            ),
            call.copy(activation = call.activation.copy(harness = call.activation.harness.copy(revision = 99))),
            call.copy(workspace = WorkspaceRef("other")),
            call.copy(process = call.process.copy(incarnation = "other")),
            call.copy(authority = ProcessAuthority.Release),
            call.copy(command = call.command.copy(arguments = listOf("other"))),
        )
        for (changed in changes) {
            assertEquals(ScriptProcessCommandResult.Unavailable, env.executor().read(changed, pending.id))
        }
        env.access.isActive = false
        assertEquals(ScriptProcessCommandResult.Unavailable, env.executor().read(call, pending.id))
        env.access.isActive = true
        env.toggles.isEnabled = false
        assertEquals(ScriptProcessCommandResult.Unavailable, env.executor().read(call, pending.id))
        assertEquals(1, env.local.executions.size)
    }

    @Test
    fun `lost acknowledgement after a durable write restores the receipt without another execution`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        val call = env.invocation()
        val pending = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(call))
        env.approve()
        env.local.beforeExecute = { env.secrets.afterWrite = { error("save acknowledgement lost") } }
        assertEquals(ScriptProcessCommandResult.Unavailable, env.executor().execute(call))
        env.secrets.afterWrite = {}
        env.restart()
        val expected = ScriptProcessCommandResult.Recorded(ProcessCommandReceipt.Exited(0))
        assertEquals(expected, env.executor().read(call, pending.id))
        assertEquals(expected, env.executor().execute(call))
        assertEquals(1, env.local.executions.size)
    }

    @Test
    fun `failure to persist the outcome leaves a consumed claim and never reruns`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        val call = env.invocation()
        val pending = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(call))
        env.approve()
        env.local.beforeExecute = { env.secrets.beforeWrite = { error("receipt unavailable") } }
        assertEquals(ScriptProcessCommandResult.Unavailable, env.executor().execute(call))
        env.secrets.beforeWrite = {}
        env.restart()
        val result = assertIs<ScriptProcessCommandResult.Decision>(env.executor().read(call, pending.id))
        assertEquals(ProcessDecisionPhase.Executing, result.phase)
        assertEquals(result, env.executor().execute(call))
        assertEquals(1, env.local.executions.size)
    }
}
