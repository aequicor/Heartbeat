package io.aequicor.heartbeat.feature.harness.impl.data.decision

import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionCommand
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteResult
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.workflowKey
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubOperation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val MERGED_RESULT = ResultRef("github_merge_commit", "b".repeat(40))

class GitHubHostReceiptTest {
    @Test
    fun `forged cached result cannot complete host decision without remote evidence`() = runTest {
        val fixture = GitHubDecisionTestEnv(this)
        fixture.initialize()
        val id = fixture.approve()
        fixture.remote.losesResponse = true
        assertEquals(GitHubWriteResult.Unknown, fixture.write())
        fixture.remote.isMerged = false
        fixture.env.clock.seconds += 5
        fixture.changeOperation { it.copy(result = ResultRef("github_merge_commit", "f".repeat(40))) }

        assertEquals(GitHubWriteResult.Unknown, fixture.service().reconcile(fixture.env.process, DECISION_KEY))
        assertEquals(ProcessDecisionPhase.Executing, fixture.env.host.ready()!!.find(id)!!.decision.phase)
        assertEquals(1, fixture.remote.writes)
    }

    @Test
    fun `crash after caching result completes original decision only after a fresh read`() = runTest {
        val fixture = GitHubDecisionTestEnv(this)
        fixture.initialize()
        val id = fixture.approve()
        fixture.remote.losesResponse = true
        assertEquals(GitHubWriteResult.Unknown, fixture.write())
        fixture.changeOperation { it.copy(result = MERGED_RESULT) }
        fixture.env.restart()
        fixture.env.clock.seconds += 5
        fixture.env.processes.apply(fixture.env.process, ProcessCommand.ReviseInput(2))

        assertEquals(
            GitHubWriteResult.Confirmed(MERGED_RESULT),
            fixture.service().reconcile(fixture.env.process, DECISION_KEY),
        )
        assertEquals(ProcessDecisionPhase.Completed, fixture.env.host.ready()!!.find(id)!!.decision.phase)
        assertEquals(1, fixture.remote.writes)
    }

    @Test
    fun `legacy reservation without binding copy still requires proof and exact host identity`() = runTest {
        val fixture = GitHubDecisionTestEnv(this)
        fixture.initialize()
        val id = fixture.approve()
        fixture.remote.losesResponse = true
        assertEquals(GitHubWriteResult.Unknown, fixture.write())
        fixture.changeOperation { it.copy(result = MERGED_RESULT, decisionBinding = null) }
        fixture.env.restart()
        fixture.env.clock.seconds += 5
        fixture.remote.isMerged = false
        assertEquals(GitHubWriteResult.Unknown, fixture.service().reconcile(fixture.env.process, DECISION_KEY))
        assertEquals(ProcessDecisionPhase.Executing, fixture.env.host.ready()!!.find(id)!!.decision.phase)
        fixture.remote.isMerged = true
        assertEquals(
            GitHubWriteResult.Confirmed(MERGED_RESULT),
            fixture.service().reconcile(fixture.env.process, DECISION_KEY),
        )
        assertEquals(ProcessDecisionPhase.Completed, fixture.env.host.ready()!!.find(id)!!.decision.phase)
    }

    @Test
    fun `another process decision id and binding cannot be completed by old receipt`() = runTest {
        val fixture = GitHubDecisionTestEnv(this)
        fixture.initialize()
        val original = fixture.approve()
        fixture.remote.losesResponse = true
        assertEquals(GitHubWriteResult.Unknown, fixture.write())
        fixture.env.clock.seconds += 5
        val second = ProcessId("another_process")
        fixture.env.processes.open(second, 1)
        fixture.env.processes.apply(second, ProcessCommand.ObserveSubject(DECISION_HEAD))
        fixture.env.processes.apply(second, ProcessCommand.Grant(ProcessAuthority.Merge))
        val context = fixture.context.copy(
            attempt = fixture.env.processes.get(second)!!.workflowKey(StageName("merge")),
        )
        val pending = assertIs<GitHubWriteResult.Decision>(
            fixture.service().write(second, 1, DECISION_KEY, DECISION_REPO, DECISION_CHANGE, context),
        )
        assertTrue(
            fixture.env.host.edit(
                ProcessDecisionEdit.Change(
                    pending.id,
                    ProcessDecisionCommand.Answer(0, true),
                ),
            ),
        )
        val other = fixture.env.host.ready()!!.find(pending.id)!!.decision.binding
        assertTrue(
            fixture.env.host.edit(
                ProcessDecisionEdit.Change(
                    pending.id,
                    ProcessDecisionCommand.Claim(other),
                ),
            ),
        )
        fixture.changeOperation { it.copy(decisionId = pending.id, decisionBinding = other, result = MERGED_RESULT) }

        assertEquals(
            GitHubWriteResult.Confirmed(MERGED_RESULT),
            fixture.service().reconcile(fixture.env.process, DECISION_KEY),
        )
        assertEquals(ProcessDecisionPhase.Executing, fixture.env.host.ready()!!.find(pending.id)!!.decision.phase)
        assertEquals(ProcessDecisionPhase.Executing, fixture.env.host.ready()!!.find(original)!!.decision.phase)
    }
}

/** ScriptData can be edited by its owner; host completion must never treat these bytes as a trusted receipt. */
private suspend fun GitHubDecisionTestEnv.changeOperation(transform: (GitHubOperation) -> GitHubOperation) {
    env.scriptData.update("hb_github") { current ->
        val stored = checkNotNull(current).jsonObject
        val operations = stored.getValue("operations") as JsonArray
        JsonObject(
            stored + (
                "operations" to JsonArray(
                    operations.map {
                        Json.encodeToJsonElement(transform(Json.decodeFromJsonElement<GitHubOperation>(it)))
                    },
                )
            ),
        )
    }
}
