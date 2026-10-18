package io.aequicor.heartbeat.feature.harness.impl.data.decision

import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionCommand
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubQuery
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteResult
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcessCommandResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubProcessAuthorityTest {
    @Test
    fun `pending exact payload survives restart without reserving a write`() = runTest {
        val fixture = GitHubDecisionTestEnv(this)
        fixture.initialize()
        val pending = assertIs<GitHubWriteResult.Decision>(fixture.write())
        assertEquals(ProcessDecisionPhase.Pending, pending.phase)
        assertNull(fixture.env.scriptData.read("hb_github").value)
        assertTrue(fixture.env.processes.get(fixture.env.process)!!.writes.isEmpty())
        fixture.env.restart()
        assertEquals(pending, fixture.write())
        val entry = fixture.env.host.ready()!!.entries.single()
        assertEquals(DECISION_CHANGE, entry.github?.change)
        assertNull(entry.command)
        assertEquals(0, fixture.remote.writes)
    }

    @Test
    fun `approval permits one conditional merge and consumes the decision durably`() = runTest {
        val fixture = GitHubDecisionTestEnv(this)
        fixture.initialize()
        val id = fixture.approve()
        assertIs<GitHubWriteResult.Confirmed>(fixture.write())
        assertTrue(fixture.remote.lastWrite!!.body.toString().contains(DECISION_HEAD.value))
        fixture.env.restart()
        assertIs<GitHubWriteResult.Confirmed>(fixture.write())
        assertEquals(ProcessDecisionPhase.Completed, fixture.env.host.ready()!!.find(id)!!.decision.phase)
        assertEquals(1, fixture.remote.writes)
    }

    @Test
    fun `denial revocation and expiration never reserve or send a write`() = runTest {
        for (mode in listOf("deny", "revoke", "expire")) {
            val fixture = GitHubDecisionTestEnv(this)
            fixture.initialize()
            val pending = assertIs<GitHubWriteResult.Decision>(fixture.write())
            fixture.env.host.edit(
                ProcessDecisionEdit.Change(pending.id, ProcessDecisionCommand.Answer(0, mode != "deny")),
            )
            if (mode == "revoke") {
                fixture.env.host.edit(ProcessDecisionEdit.Change(pending.id, ProcessDecisionCommand.Revoke(1)))
            }
            if (mode == "expire") fixture.env.clock.seconds += 24 * 60 * 60
            assertIs<GitHubWriteResult.Decision>(fixture.write())
            assertNull(fixture.env.scriptData.read("hb_github").value)
            assertEquals(0, fixture.remote.writes)
        }
    }

    @Test
    fun `lost merge response reconciles after restart without a second send`() = runTest {
        val fixture = GitHubDecisionTestEnv(this)
        fixture.initialize()
        val id = fixture.approve()
        fixture.remote.losesResponse = true
        assertEquals(GitHubWriteResult.Unknown, fixture.write())
        assertEquals(ProcessDecisionPhase.Executing, fixture.env.host.ready()!!.find(id)!!.decision.phase)
        fixture.env.restart()
        fixture.env.processes.apply(fixture.env.process, ProcessCommand.ReviseInput(2))
        fixture.env.clock.seconds += 5
        assertIs<GitHubWriteResult.Confirmed>(fixture.service().reconcile(fixture.env.process, DECISION_KEY))
        assertEquals(ProcessDecisionPhase.Completed, fixture.env.host.ready()!!.find(id)!!.decision.phase)
        assertEquals(1, fixture.remote.writes)
    }

    @Test
    fun `consumed host identity survives a reset of script owned registry`() = runTest {
        for (lost in listOf(false, true)) {
            val fixture = GitHubDecisionTestEnv(this)
            fixture.initialize()
            fixture.approve()
            fixture.remote.losesResponse = lost
            fixture.write()
            val stored = fixture.env.scriptData.read("hb_github")
            fixture.env.scriptData.write("hb_github", null, stored.revision)
            fixture.env.restart()
            assertEquals(GitHubWriteResult.Unknown, fixture.write())
            assertNull(fixture.env.scriptData.read("hb_github").value)
            assertEquals(1, fixture.remote.writes)
        }
    }

    @Test
    fun `changes in key payload workspace stage or input cannot reuse approval`() = runTest {
        for (mode in listOf("key", "payload", "workspace", "stage", "input", "head", "origin", "scope")) {
            val fixture = GitHubDecisionTestEnv(this)
            fixture.initialize()
            fixture.approve()
            var key = DECISION_KEY
            var change = DECISION_CHANGE
            var context = fixture.context
            when (mode) {
                "key" -> key = WriteKey("other")
                "payload" -> change = DECISION_CHANGE.copy(number = 2)
                "workspace" -> context = context.copy(workspace = context.workspace.copy(value = "other"))
                "stage" -> context = context.copy(attempt = context.attempt.copy(stage = StageName("other")))
                "input" -> fixture.env.processes.apply(fixture.env.process, ProcessCommand.ReviseInput(2))
                "head" -> fixture.env.local.head = "b".repeat(40)
                "origin" -> fixture.env.local.repository = "https://github.com/owner/other.git"
                "scope" -> fixture.isWithinScope = false
            }
            fixture.write(change, key, context)
            assertEquals(0, fixture.remote.writes, mode)
        }
    }

    @Test
    fun `revocation and live changes during API queue prevent final dispatch`() = runTest {
        for (mode in listOf("revoke", "head", "origin", "toggle", "expiry", "access")) {
            val fixture = GitHubDecisionTestEnv(this)
            fixture.initialize()
            val id = fixture.approve()
            fixture.remote.afterPreflight = {
                fixture.remote.afterPreflight = {}
                backgroundScope.async { fixture.api.read(DECISION_REPO, GitHubQuery.Commit("hold")) }
                runCurrent()
            }
            val writer = async { fixture.write() }
            fixture.remote.held.await()
            runCurrent()
            when (mode) {
                "revoke" -> fixture.env.host.edit(ProcessDecisionEdit.Change(id, ProcessDecisionCommand.Revoke(1)))
                "head" -> fixture.env.local.head = "b".repeat(40)
                "origin" -> fixture.env.local.repository = "https://github.com/owner/other.git"
                "toggle" -> fixture.env.toggles.isEnabled = false
                "expiry" -> fixture.env.clock.seconds += 24 * 60 * 60
                "access" -> fixture.env.access.isActive = false
            }
            fixture.remote.release.complete(Unit)
            assertEquals(GitHubWriteResult.Unknown, writer.await(), mode)
            assertEquals(0, fixture.remote.writes, mode)
        }
    }

    @Test
    fun `release and dispatch are refused before a native decision or reservation`() = runTest {
        val changes = listOf(
            GitHubChange.CreateRelease("v1", DECISION_HEAD, "private release notes"),
            GitHubChange.Dispatch(42, "master", DECISION_HEAD, mapOf("target" to "production")),
        )
        for (change in changes) {
            val fixture = GitHubDecisionTestEnv(this)
            fixture.initialize()
            assertEquals(GitHubWriteResult.Refused(GitHubWriteRefusal.UnsafeReference), fixture.write(change))
            assertTrue(fixture.env.host.ready()!!.entries.isEmpty())
            assertNull(fixture.env.scriptData.read("hb_github").value)
            assertTrue(fixture.env.processes.get(fixture.env.process)!!.writes.isEmpty())
            assertEquals(0, fixture.remote.writes)
        }
    }

    @Test
    fun `dispatch input ordering cannot bypass the unsafe reference refusal`() = runTest {
        val fixture = GitHubDecisionTestEnv(this)
        fixture.initialize()
        val first = GitHubChange.Dispatch(42, "master", DECISION_HEAD, linkedMapOf("a" to "1", "b" to "2"))
        val second = first.copy(inputs = linkedMapOf("b" to "2", "a" to "1"))
        assertEquals(GitHubWriteResult.Refused(GitHubWriteRefusal.UnsafeReference), fixture.write(first))
        assertEquals(GitHubWriteResult.Refused(GitHubWriteRefusal.UnsafeReference), fixture.write(second))
        assertTrue(fixture.env.host.ready()!!.entries.isEmpty())
        assertNull(fixture.env.scriptData.read("hb_github").value)
    }

    @Test
    fun `command executor cannot claim a typed GitHub decision`() = runTest {
        val fixture = GitHubDecisionTestEnv(this)
        fixture.initialize()
        fixture.approve()
        assertIs<ScriptProcessCommandResult.Decision>(fixture.env.executor().execute(fixture.env.invocation()))
        assertTrue(fixture.env.local.executions.isEmpty())
        assertEquals(0, fixture.remote.writes)
        assertEquals(2, fixture.env.host.ready()!!.entries.size)
    }

    @Test
    fun `missing context fails closed and concurrent identical calls send once`() = runTest {
        val fixture = GitHubDecisionTestEnv(this)
        fixture.initialize()
        assertIs<GitHubWriteResult.Refused>(fixture.write(context = null))
        fixture.approve()
        val first = async { fixture.write() }
        val second = async { fixture.write() }
        first.await()
        second.await()
        assertEquals(1, fixture.remote.writes)
    }
}
