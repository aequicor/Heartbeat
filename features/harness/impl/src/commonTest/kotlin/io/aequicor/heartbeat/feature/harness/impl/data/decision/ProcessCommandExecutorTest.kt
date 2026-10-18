package io.aequicor.heartbeat.feature.harness.impl.data.decision

import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionCommand
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.WaitReason
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcessCommandResult
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessCommandInvocation
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProcessCommandExecutorTest {
    @Test
    fun `script grants do not authorize an action and pending requests survive restart`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        env.processes.apply(env.process, ProcessCommand.Grant(ProcessAuthority.Merge))
        val pending = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(env.invocation()))
        assertEquals(ProcessDecisionPhase.Pending, pending.phase)
        env.restart()
        assertEquals(pending, env.executor().execute(env.invocation()))
        assertTrue(env.local.executions.isEmpty())
        assertEquals(1, env.host.ready()?.entries?.size)
    }

    @Test
    fun `human approval executes exact words once and restart cannot repeat the dispatch`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        env.approve()
        val result = assertIs<ScriptProcessCommandResult.Finished>(env.executor().execute(env.invocation()))
        assertEquals(ScriptCommandResult.Exited(0, "done"), result.result)
        assertEquals(listOf("pr", "merge", "1"), env.local.executions.single().arguments)
        env.restart()
        val repeated = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(env.invocation()))
        assertEquals(ProcessDecisionPhase.Completed, repeated.phase)
        assertEquals(1, env.local.executions.size)
    }

    @Test
    fun `parked process resumes the same approved key after restart without another prompt`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        val call = env.invocation()
        val pending = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(call))
        env.processes.apply(
            env.process,
            ProcessCommand.Wait(WaitReason.Human, WakeCondition(deadline = env.clock.now() + 30.seconds)),
        )
        env.restart()
        val restored = assertIs<ProcessPhase.Waiting>(env.processes.get(env.process)?.phase)
        assertEquals(pending, env.executor().execute(env.invocation()))
        assertTrue(env.host.edit(ProcessDecisionEdit.Change(pending.id, ProcessDecisionCommand.Answer(0, true))))
        env.processes.apply(env.process, ProcessCommand.Resume(restored.wait.number))
        assertIs<ScriptProcessCommandResult.Finished>(env.executor().execute(call))
        val tick = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(call))
        assertEquals(ProcessDecisionPhase.Completed, tick.phase)
        assertEquals(1, env.local.executions.size)
        assertEquals(1, env.host.ready()?.entries?.size)
    }

    @Test
    fun `revoke denial and expiry prevent execution without creating another request`() = runTest {
        for (mode in listOf("revoke", "deny", "expire")) {
            val env = ProcessCommandTestEnv(this)
            env.initialize()
            val pending = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(env.invocation()))
            if (mode == "deny") {
                env.host.edit(ProcessDecisionEdit.Change(pending.id, ProcessDecisionCommand.Answer(0, false)))
            } else {
                env.host.edit(ProcessDecisionEdit.Change(pending.id, ProcessDecisionCommand.Answer(0, true)))
                if (mode == "revoke") {
                    env.host.edit(ProcessDecisionEdit.Change(pending.id, ProcessDecisionCommand.Revoke(1)))
                } else {
                    env.clock.seconds += 24 * 60 * 60
                }
            }
            env.executor().execute(env.invocation())
            assertTrue(env.local.executions.isEmpty())
            assertEquals(1, env.host.ready()?.entries?.size)
        }
    }

    @Test
    fun `changed input repository operation and command cannot reuse consent`() = runTest {
        for (mode in listOf("input", "repository", "operation", "command")) {
            val env = ProcessCommandTestEnv(this)
            env.initialize()
            env.approve()
            val old = env.invocation()
            val next = when (mode) {
                "input" -> {
                    env.processes.apply(env.process, ProcessCommand.ReviseInput(2))
                    env.invocation()
                }

                "repository" -> {
                    env.local.repository = "git@github.com:owner/other.git"
                    env.invocation()
                }

                "operation" -> ProcessCommandInvocation(
                    old.activation,
                    old.access,
                    old.process,
                    old.workspace,
                    ProcessAuthority.Release,
                    old.command,
                )

                else -> ProcessCommandInvocation(
                    old.activation,
                    old.access,
                    old.process,
                    old.workspace,
                    old.authority,
                    old.command.copy(arguments = listOf("pr", "merge", "2")),
                )
            }
            val result = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(next))
            assertEquals(ProcessDecisionPhase.Pending, result.phase)
            assertTrue(env.local.executions.isEmpty())
        }
    }

    @Test
    fun `changed live HEAD or disabled feature cannot execute an approved command`() = runTest {
        for (mode in listOf("head", "toggle", "activation")) {
            val env = ProcessCommandTestEnv(this)
            env.initialize()
            env.approve()
            val call = env.invocation()
            when (mode) {
                "head" -> env.local.head = "b".repeat(40)
                "toggle" -> env.toggles.isEnabled = false
                else -> env.access.isActive = false
            }
            assertEquals(ScriptProcessCommandResult.Unavailable, env.executor().execute(call))
            assertTrue(env.local.executions.isEmpty())
        }
    }

    @Test
    fun `HEAD changed during inspection prevents admission without dispatch`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        env.approve()
        var reads = 0
        env.local.afterRemoteRead = {
            reads++
            if (reads == 1) env.local.head = "c".repeat(40)
        }
        assertEquals(ScriptProcessCommandResult.Unavailable, env.executor().execute(env.invocation()))
        assertTrue(env.local.executions.isEmpty())
        assertEquals(ProcessDecisionPhase.Approved, env.host.ready()?.entries?.single()?.decision?.phase)
    }

    @Test
    fun `revocation or expiry during final inspection prevents admission`() = runTest {
        for (mode in listOf("revoke", "expire")) {
            val env = ProcessCommandTestEnv(this)
            env.initialize()
            env.approve()
            var reads = 0
            env.local.afterRemoteRead = {
                reads++
                if (reads == 2) {
                    val decision = checkNotNull(env.host.ready()).entries.single().decision
                    if (mode == "revoke") {
                        env.host.edit(
                            ProcessDecisionEdit.Change(decision.id, ProcessDecisionCommand.Revoke(decision.revision)),
                        )
                    } else {
                        env.clock.seconds += 24 * 60 * 60
                    }
                }
            }
            assertEquals(ScriptProcessCommandResult.Unavailable, env.executor().execute(env.invocation()))
            assertTrue(env.local.executions.isEmpty())
        }
    }

    @Test
    fun `expiry during durable claim commit does not dispatch the external command`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        env.approve()
        env.secrets.beforeWrite = { env.clock.seconds += 24 * 60 * 60 }
        assertEquals(ScriptProcessCommandResult.Unavailable, env.executor().execute(env.invocation()))
        assertTrue(env.local.executions.isEmpty())
        assertEquals(ProcessDecisionPhase.Executing, env.host.ready()?.entries?.single()?.decision?.phase)
    }

    @Test
    fun `raw script data writes cannot replace the process while the executor holds admission`() = runTest {
        val env = ProcessCommandTestEnv(this)
        env.initialize()
        env.approve()
        val stored = env.scriptData.read(HarnessScriptProcesses.STORAGE_KEY)
        val released = CompletableDeferred<Unit>()
        env.local.beforeExecute = { released.await() }
        val call = env.invocation()
        val running = async { env.executor().execute(call) }
        runCurrent()
        val attacker = async { env.scriptData.write(HarnessScriptProcesses.STORAGE_KEY, null, stored.revision) }
        runCurrent()
        assertFalse(attacker.isCompleted)
        assertEquals(stored, env.scriptData.read(HarnessScriptProcesses.STORAGE_KEY))
        released.complete(Unit)
        assertIs<ScriptProcessCommandResult.Finished>(running.await())
        attacker.await()
        assertNull(env.scriptData.read(HarnessScriptProcesses.STORAGE_KEY).value)
    }

    @Test
    fun `timeout and cancellation keep an unresolved consumed claim after restart`() = runTest {
        for (mode in listOf("timeout", "cancel")) {
            val env = ProcessCommandTestEnv(this)
            env.initialize()
            env.approve()
            if (mode == "timeout") {
                env.local.outcome = LocalCommandOutcome.TimedOut("partial")
                assertIs<ScriptProcessCommandResult.Finished>(env.executor().execute(env.invocation()))
            } else {
                env.local.beforeExecute = { throw CancellationException("cancelled") }
                assertFailsWith<CancellationException> { env.executor().execute(env.invocation()) }
            }
            env.restart()
            val result = assertIs<ScriptProcessCommandResult.Decision>(env.executor().execute(env.invocation()))
            assertEquals(ProcessDecisionPhase.Executing, result.phase)
            assertEquals(1, env.local.executions.size)
        }
    }
}

private suspend fun ProcessCommandTestEnv.approve() {
    val pending = assertIs<ScriptProcessCommandResult.Decision>(executor().execute(invocation()))
    assertTrue(host.edit(ProcessDecisionEdit.Change(pending.id, ProcessDecisionCommand.Answer(0, true))))
}
