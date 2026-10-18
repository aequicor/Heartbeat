package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteResult
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.api.process.WritePhase
import io.aequicor.heartbeat.feature.harness.api.script.ScriptData
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandOrigins
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val REPOSITORY = GitHubRepository("owner", "repo")
private val PROCESS = ProcessId("work")
private val KEY = WriteKey("create_issue")
private val CHANGE = GitHubChange.CreateIssue("title", "untrusted text")

class GitHubWritesTest {
    @Test
    fun `lost response reconciles once after restart without another post`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val first = ProcessesTestEnv(this, secrets)
        first.processes.open(PROCESS)
        first.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Write))
        val remote = IssueGitHub().apply { losesResponse = true }
        assertEquals(GitHubWriteResult.Unknown, service(first, remote).write(PROCESS, 0, KEY, REPOSITORY, CHANGE))
        val restarted = ProcessesTestEnv(this, secrets, restartedAt = 100)
        val outcome = service(restarted, remote).write(PROCESS, 0, KEY, REPOSITORY, CHANGE)
        assertEquals(GitHubWriteResult.Confirmed(ResultRef("github_issue", "1")), outcome)
        assertEquals(1, remote.posts)
        assertIs<WritePhase.Confirmed>(restarted.processes.get(PROCESS)!!.writes.single().phase)
    }

    @Test
    fun `crash before external call retains reservation and missing marker never permits retry`() = runTest {
        val env = ProcessesTestEnv(this)
        val record = env.processes.open(PROCESS)
        env.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Write))
        GitHubRegistry(env.data).reserve(GitHubOperation(PROCESS, record.incarnation, 0, KEY, REPOSITORY, CHANGE))
        val remote = IssueGitHub()
        val github = service(env, remote)
        repeat(3) {
            assertEquals(GitHubWriteResult.Unknown, github.write(PROCESS, 0, KEY, REPOSITORY, CHANGE))
        }
        assertEquals(0, remote.posts)
    }

    @Test
    fun `crash after server response before durable confirmation recovers by marker`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(PROCESS)
        env.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Write))
        var failsSettlement = false
        val data = object : ScriptData by env.data {
            override suspend fun update(key: String, transform: (JsonElement?) -> JsonElement?) =
                if (key == "hb_github" && failsSettlement) error("simulated crash") else env.data.update(key, transform)
        }
        val remote = IssueGitHub().apply { afterPost = { failsSettlement = true } }
        assertFailsWith<IllegalStateException> {
            service(env, remote, data).write(PROCESS, 0, KEY, REPOSITORY, CHANGE)
        }
        failsSettlement = false
        val outcome = service(env, remote).reconcile(PROCESS, KEY)
        assertEquals(GitHubWriteResult.Confirmed(ResultRef("github_issue", "1")), outcome)
        assertEquals(1, remote.posts)
    }

    @Test
    fun `permanent keys survive eviction from the eight local process slots`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(PROCESS)
        env.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Write))
        val remote = IssueGitHub()
        val github = service(env, remote)
        repeat(12) { index ->
            assertIs<GitHubWriteResult.Confirmed>(
                github.write(PROCESS, 0, WriteKey("issue_$index"), REPOSITORY, CHANGE),
            )
        }
        assertEquals(8, env.processes.get(PROCESS)!!.writes.size)
        assertEquals(
            GitHubWriteResult.Confirmed(ResultRef("github_issue", "1")),
            service(env, remote).write(PROCESS, 0, WriteKey("issue_0"), REPOSITORY, CHANGE),
        )
        assertEquals(12, remote.posts)
    }

    @Test
    fun `concurrent activations reserve one sender and request changes conflict`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(PROCESS)
        env.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Write))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val remote = IssueGitHub().apply {
            beforePost = {
                entered.complete(Unit)
                release.await()
            }
        }
        val first = async { service(env, remote).write(PROCESS, 0, KEY, REPOSITORY, CHANGE) }
        entered.await()
        val second = async { service(env, remote).write(PROCESS, 0, KEY, REPOSITORY, CHANGE) }
        runCurrent()
        release.complete(Unit)
        assertIs<GitHubWriteResult.Confirmed>(first.await())
        assertTrue(second.await() is GitHubWriteResult.Unknown || remote.posts == 1)
        assertEquals(1, remote.posts)
        assertEquals(
            GitHubWriteResult.Refused(GitHubWriteRefusal.KeyConflict),
            service(env, remote).write(PROCESS, 0, KEY, REPOSITORY, CHANGE.copy(body = "different")),
        )
    }

    @Test
    fun `cached GitHub result never settles another target reusing an evicted local key`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(PROCESS)
        env.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Write))
        val github = service(env, IssueGitHub())
        val original = github.write(PROCESS, 0, KEY, REPOSITORY, CHANGE)
        repeat(9) { index ->
            github.write(PROCESS, 0, WriteKey("evict_$index"), REPOSITORY, CHANGE)
        }
        env.processes.apply(
            PROCESS,
            ProcessCommand.BeginWrite(KEY, ProcessAuthority.Write, ResultRef("other_system", "another_call")),
        )
        val foreign = env.processes.get(PROCESS)!!.write(KEY)
        assertEquals(original, github.reconcile(PROCESS, KEY))
        assertEquals(foreign, env.processes.get(PROCESS)!!.write(KEY))
    }

    @Test
    fun `script grants never authorize merge release or workflow execution`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(PROCESS)
        env.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Merge))
        env.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Release))
        val remote = IssueGitHub()
        val github = service(env, remote)
        val sha = SubjectSha("a".repeat(40))
        for (change in listOf(
            GitHubChange.Merge(1, sha),
            GitHubChange.CreateRelease("v1", sha, ""),
            GitHubChange.Dispatch(1, "master", sha),
        )) {
            val refusal = if (change is GitHubChange.Merge) {
                GitHubWriteRefusal.NotAuthorized
            } else {
                GitHubWriteRefusal.UnsafeReference
            }
            assertEquals(GitHubWriteResult.Refused(refusal), github.write(PROCESS, 0, KEY, REPOSITORY, change))
        }
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `unreadable registry fails closed without any GitHub request`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(PROCESS)
        env.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Write))
        env.data.update("hb_github") { JsonPrimitive("private damaged payload") }
        val remote = IssueGitHub()
        val failure = assertFailsWith<IllegalStateException> {
            service(env, remote).write(PROCESS, 0, KEY, REPOSITORY, CHANGE)
        }
        assertEquals("Stored GitHub registry is unreadable", failure.message)
        assertTrue(remote.calls.isEmpty())
    }
}

private fun service(env: ProcessesTestEnv, transport: GitHubTransport, data: ScriptData = env.data) =
    HarnessScriptGitHub(
        env.access,
        CommandOrigins(),
        env.processes,
        GitHubRegistry(data),
        GitHubHost(
            GitHubApi(transport, env.clock),
            GitHubHostAuthority { false },
            { true },
        ),
        env.processGate,
    )

private class IssueGitHub : GitHubTransport {
    override val isAvailable = true
    val calls = mutableListOf<GitHubRequest>()
    val issues = mutableListOf<JsonObject>()
    var posts = 0
    var losesResponse = false
    var beforePost: suspend () -> Unit = {}
    var afterPost: () -> Unit = {}

    override suspend fun call(request: GitHubRequest): GitHubResponse {
        calls += request
        if (request.method == "GET") return GitHubResponse(200, JsonArray(issues.toList()))
        beforePost()
        posts++
        val body = request.body!!.jsonObject
        val issue = buildJsonObject {
            put("id", posts)
            put("number", posts)
            put("node_id", "I_$posts")
            put("title", body.getValue("title"))
            put("body", body.getValue("body"))
            put("state", "open")
            put("updated_at", "2026-10-18T12:00:00Z")
        }
        issues += issue
        afterPost()
        return if (losesResponse) GitHubResponse(0) else GitHubResponse(201, issue)
    }
}
