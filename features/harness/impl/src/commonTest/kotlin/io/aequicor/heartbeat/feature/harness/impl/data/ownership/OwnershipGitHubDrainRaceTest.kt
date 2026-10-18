package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteResult
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ScriptOwnershipResult
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.api.process.WritePhase
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.PortableGitHubAdapter
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.ProtectedImportedGitHub
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubApi
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubHost
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubHostAuthority
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRegistry
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubResponse
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubTransport
import io.aequicor.heartbeat.feature.harness.impl.domain.github.HarnessScriptGitHub
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnershipGitHubDrainRaceTest {
    @Test
    fun `drain closes unsent GitHub reservation before paused producer resumes after cancellation`() = runTest {
        val server = GitHubOwnershipBackendTest.Server()
        val source = OwnershipRuntimeTestEnv(this, server)
        val target = OwnershipRuntimeTestEnv(this, server)
        val process = ProcessId("github_race")
        val repository = GitHubRepository("owner", "repo")
        source.processes.open(process, 1)
        source.approve(source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace))
        source.ownership.enroll(process, repository, GitHubTaskKind.Issue, 1, source.workspace)
        source.processes.apply(process, ProcessCommand.Grant(ProcessAuthority.Write))
        val transport = PausedPreflight()
        val client = client(source, transport)
        val key = WriteKey("draft_pull")
        val write = async {
            client.write(
                process,
                1,
                key,
                repository,
                GitHubChange.CreatePull("Draft", "Body", "branch", "main", SubjectSha("a".repeat(40))),
            )
        }
        transport.entered.await()
        assertEquals(false, registry(source).find(process, source.record(process).incarnation, key)?.hasSendStarted)
        val targetHost = checkNotNull(target.ownership.host())
        source.approve(source.ownership.prepare(process, targetHost, source.workspace))
        source.ownership.prepare(process, targetHost, source.workspace)
        source.ownership.advance(process, source.workspace)
        val exported = assertIs<ScriptOwnershipResult.Current>(source.ownership.advance(process, source.workspace))
        assertEquals(OwnershipPhase.Exported, exported.ownership.phase)
        assertIs<WritePhase.NotApplied>(source.record(process).write(key)?.phase)
        assertTrue(
            assertIs<ScriptOwnershipResult.Current>(source.ownership.status(process)).ownership.permits.isEmpty(),
        )
        source.approve(source.ownership.cancel(process, source.workspace))
        source.ownership.cancel(process, source.workspace)
        val active = assertIs<ScriptOwnershipResult.Current>(source.ownership.advance(process, source.workspace))
        assertEquals(OwnershipPhase.Active, active.ownership.phase)
        transport.resume.complete(Unit)
        assertEquals(GitHubWriteResult.Unknown, write.await())
        assertEquals(0, transport.posts)
        assertIs<GitHubWriteResult.Refused>(client.reconcile(process, key))
    }

    private fun registry(source: OwnershipRuntimeTestEnv) =
        GitHubRegistry(HarnessScriptData(harness.id, source.access, source.data).processData())

    private fun client(source: OwnershipRuntimeTestEnv, transport: GitHubTransport): HarnessScriptGitHub {
        val registry = registry(source)
        val api = GitHubApi(transport, source.clock)
        return HarnessScriptGitHub(
            source.access,
            CommandOrigins(),
            source.processes,
            registry,
            GitHubHost(api, GitHubHostAuthority { true }) { true },
            source.data.processMutex,
            PortableGitHubAdapter(api, registry, ProtectedImportedGitHub(source.secrets)),
            harness.id,
        )
    }

    private class PausedPreflight : GitHubTransport {
        override val isAvailable = true
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var posts = 0
            private set

        override suspend fun call(request: GitHubRequest): GitHubResponse {
            if (request.method == "POST") posts++
            check(request.method == "GET" && "/commits/" in request.path)
            entered.complete(Unit)
            resume.await()
            return GitHubResponse(200, buildJsonObject { put("sha", "a".repeat(40)) })
        }
    }
}
