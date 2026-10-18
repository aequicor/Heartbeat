package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteResult
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandOrigins
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestEnv
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubDispatchTest {
    @Test
    fun `mutable remote references cannot send even when host admission would allow`() = runTest {
        val head = SubjectSha("a".repeat(40))
        val changes = listOf(
            GitHubChange.Dispatch(2, "master", head),
            GitHubChange.CreateRelease("v1", head, "notes"),
        )
        for (change in changes) {
            val env = ProcessesTestEnv(this)
            val process = ProcessId("work")
            env.processes.open(process)
            env.processes.apply(process, ProcessCommand.Grant(ProcessAuthority.Release))
            var remoteHead = head
            var admissions = 0
            val transport = FakeGitHubTransport {
                // A preflight observes the approved head, then the remote reference moves before a possible POST.
                val observed = remoteHead
                remoteHead = SubjectSha("b".repeat(40))
                GitHubResponse(200, buildJsonObject { put("sha", observed.value) })
            }
            val service = HarnessScriptGitHub(
                env.access,
                CommandOrigins(),
                env.processes,
                GitHubRegistry(env.data),
                GitHubHost(
                    GitHubApi(transport, env.clock),
                    GitHubHostAuthority {
                        admissions++
                        true
                    },
                    { true },
                ),
                env.processGate,
            )
            repeat(2) {
                assertEquals(
                    GitHubWriteResult.Refused(GitHubWriteRefusal.UnsafeReference),
                    service.write(process, 0, WriteKey("unsafe"), GitHubRepository("owner", "repo"), change),
                )
                remoteHead = SubjectSha("b".repeat(40))
            }
            assertEquals(0, admissions)
            assertTrue(transport.calls.isEmpty())
            assertTrue(env.processes.get(process)!!.writes.isEmpty())
            assertNull(env.data.read("hb_github").value)
        }
    }

    @Test
    fun `legacy uncertain dispatch with no exact run evidence never dispatches again`() = runTest {
        val env = ProcessesTestEnv(this)
        val process = ProcessId("work")
        val sha = SubjectSha("a".repeat(40))
        val repository = GitHubRepository("owner", "repo")
        val key = WriteKey("dispatch")
        val record = env.processes.open(process)
        env.processes.apply(process, ProcessCommand.Grant(ProcessAuthority.Release))
        val change = GitHubChange.Dispatch(2, "master", sha)
        // Model the durable reservation left by an earlier version after losing the dispatch response.
        GitHubRegistry(env.data).reserve(GitHubOperation(process, record.incarnation, 0, key, repository, change))
        val transport = FakeGitHubTransport {
            GitHubResponse(200, buildJsonObject { put("workflow_runs", JsonArray(emptyList())) })
        }
        fun service() = HarnessScriptGitHub(
            env.access,
            CommandOrigins(),
            env.processes,
            GitHubRegistry(env.data),
            GitHubHost(GitHubApi(transport, env.clock), GitHubHostAuthority { true }, { true }),
            env.processGate,
        )
        repeat(3) {
            assertEquals(GitHubWriteResult.Unknown, service().write(process, 0, key, repository, change))
            assertEquals(GitHubWriteResult.Unknown, service().reconcile(process, key))
        }
        assertTrue(transport.calls.isNotEmpty())
        assertTrue(transport.calls.all { it.method == "GET" })
    }
}
