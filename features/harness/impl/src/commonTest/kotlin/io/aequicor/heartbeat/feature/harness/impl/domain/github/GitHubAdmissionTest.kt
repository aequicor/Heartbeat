package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubQuery
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteResult
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessEnd
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandOrigins
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val REPO = GitHubRepository("owner", "repo")
private val PROCESS = ProcessId("work")
private val HEAD = SubjectSha("a".repeat(40))

class GitHubAdmissionTest {
    @Test
    fun `input change or process close during preflight prevents post`() = runTest {
        for (command in listOf(ProcessCommand.ReviseInput(1), ProcessCommand.Close(ProcessEnd.Cancelled))) {
            val env = ProcessesTestEnv(this)
            env.processes.open(PROCESS)
            env.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Write))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val transport = FakeGitHubTransport {
                entered.complete(Unit)
                release.await()
                GitHubResponse(200, buildJsonObject { put("sha", HEAD.value) })
            }
            val github = HarnessScriptGitHub(
                env.access,
                CommandOrigins(),
                env.processes,
                GitHubRegistry(env.data),
                GitHubHost(
                    GitHubApi(transport, env.clock),
                    GitHubHostAuthority { false },
                    { true },
                ),
                env.processGate,
            )
            val write = async {
                github.write(
                    PROCESS,
                    0,
                    WriteKey("pull"),
                    REPO,
                    GitHubChange.CreatePull("title", "", "work", "master", HEAD),
                )
            }
            entered.await()
            env.processes.apply(PROCESS, command)
            release.complete(Unit)
            assertEquals(GitHubWriteResult.Unknown, write.await())
            assertTrue(transport.calls.all { it.method == "GET" })
        }
    }

    @Test
    fun `toggle off or retired activation while queued behind another read prevents post`() = runTest {
        for (retire in listOf(false, true)) {
            val env = ProcessesTestEnv(this)
            env.processes.open(PROCESS)
            env.processes.apply(PROCESS, ProcessCommand.Grant(ProcessAuthority.Write))
            var enabled = true
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val transport = FakeGitHubTransport {
                entered.complete(Unit)
                release.await()
                GitHubResponse(404)
            }
            val api = GitHubApi(transport, env.clock)
            val reader = async { api.read(REPO, GitHubQuery.Issue(1)) }
            entered.await()
            val github = HarnessScriptGitHub(
                env.access,
                CommandOrigins(),
                env.processes,
                GitHubRegistry(env.data),
                GitHubHost(
                    api,
                    GitHubHostAuthority { false },
                    { enabled },
                ),
                env.processGate,
            )
            val write = async {
                github.write(PROCESS, 0, WriteKey("issue"), REPO, GitHubChange.CreateIssue("title", "body"))
            }
            runCurrent()
            if (retire) env.access.isActive = false else enabled = false
            release.complete(Unit)
            reader.await()
            assertEquals(GitHubWriteResult.Unknown, write.await())
            assertEquals(1, transport.calls.size)
        }
    }
}
