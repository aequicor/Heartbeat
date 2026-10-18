package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
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

class GitHubDispatchTest {
    @Test
    fun `uncertain dispatch with no exact run evidence never dispatches again`() = runTest {
        val env = ProcessesTestEnv(this)
        val process = ProcessId("work")
        val sha = SubjectSha("a".repeat(40))
        env.processes.open(process)
        env.processes.apply(process, ProcessCommand.Grant(ProcessAuthority.Release))
        var dispatches = 0
        val transport = FakeGitHubTransport { request ->
            when {
                request.path.contains("/commits/") -> GitHubResponse(200, buildJsonObject { put("sha", sha.value) })

                request.method == "POST" -> {
                    dispatches++
                    GitHubResponse(204)
                }

                else -> GitHubResponse(200, buildJsonObject { put("workflow_runs", JsonArray(emptyList())) })
            }
        }
        fun service() = HarnessScriptGitHub(
            env.access,
            CommandOrigins(),
            env.processes,
            GitHubRegistry(env.data),
            GitHubHost(
                GitHubApi(transport, env.clock),
                GitHubHostAuthority { true },
                { true },
            ),
            env.processGate,
        )
        repeat(3) {
            assertEquals(
                GitHubWriteResult.Unknown,
                service().write(
                    process,
                    0,
                    WriteKey("dispatch"),
                    GitHubRepository("owner", "repo"),
                    GitHubChange.Dispatch(2, "master", sha),
                ),
            )
        }
        assertEquals(1, dispatches)
    }
}
