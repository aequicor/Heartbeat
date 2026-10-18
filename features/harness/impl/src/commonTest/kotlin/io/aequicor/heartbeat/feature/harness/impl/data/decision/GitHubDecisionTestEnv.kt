package io.aequicor.heartbeat.feature.harness.impl.data.decision

import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionCommand
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.github.GitHubActionContext
import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteResult
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCreationScope
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubApi
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubHost
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRegistry
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubResponse
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubTransport
import io.aequicor.heartbeat.feature.harness.impl.domain.github.HarnessScriptGitHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal val DECISION_REPO = GitHubRepository("owner", "repo")
internal val DECISION_KEY = WriteKey("merge_pr_1")
internal val DECISION_HEAD = SubjectSha("a".repeat(40))
internal val DECISION_CHANGE = GitHubChange.Merge(1, DECISION_HEAD)

/** Real script/process storage, encrypted decision host and GitHub service; only git and remote IO are fakes. */
internal class GitHubDecisionTestEnv(scope: TestScope) {
    val env = ProcessCommandTestEnv(scope)
    val remote = DecisionGitHubRemote()
    val api = GitHubApi(remote, env.clock)
    var isWithinScope = true
    var isGitHubEnabled = true
    lateinit var context: GitHubActionContext

    suspend fun initialize() {
        env.initialize()
        env.processes.apply(env.process, ProcessCommand.Grant(ProcessAuthority.Merge))
        env.processes.apply(env.process, ProcessCommand.Grant(ProcessAuthority.Release))
        val call = env.invocation()
        context = GitHubActionContext(call.workspace, call.process)
    }

    suspend fun service(): HarnessScriptGitHub {
        val call = env.invocation()
        val authority = GitHubProcessAuthorityFactory(
            env.host,
            env.local,
            HarnessCreationScope { if (isWithinScope) HarnessScope.Projects(setOf(context.workspace)) else null },
            env.toggles,
            env.clock,
        ).create(call.activation, env.access, env.processes)
        return HarnessScriptGitHub(
            env.access,
            CommandOrigins(),
            env.processes,
            GitHubRegistry(env.scriptData),
            GitHubHost(api, authority) { isGitHubEnabled },
            env.data.processMutex,
        )
    }

    suspend fun write(
        change: GitHubChange = DECISION_CHANGE,
        key: WriteKey = DECISION_KEY,
        context: GitHubActionContext? = this.context,
    ): GitHubWriteResult = service().write(env.process, 1, key, DECISION_REPO, change, context)

    suspend fun approve(change: GitHubChange = DECISION_CHANGE, key: WriteKey = DECISION_KEY): String {
        val pending = assertIs<GitHubWriteResult.Decision>(write(change, key))
        assertTrue(env.host.edit(ProcessDecisionEdit.Change(pending.id, ProcessDecisionCommand.Answer(0, true))))
        return pending.id
    }
}

internal class DecisionGitHubRemote : GitHubTransport {
    override val isAvailable = true
    var writes = 0
    var isMerged = false
    var losesResponse = false
    var afterPreflight: suspend () -> Unit = {}
    val held = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var lastWrite: GitHubRequest? = null

    override suspend fun call(request: GitHubRequest): GitHubResponse = when {
        request.path.endsWith("/commits/hold") -> {
            held.complete(Unit)
            release.await()
            GitHubResponse(200, body = buildJsonObject { put("sha", DECISION_HEAD.value) })
        }

        request.method == "GET" -> read(request)

        else -> {
            writes++
            lastWrite = request
            isMerged = true
            if (losesResponse) {
                GitHubResponse(0)
            } else {
                GitHubResponse(
                    200,
                    body = buildJsonObject {
                        put("merged", true)
                        put("sha", "b".repeat(40))
                        put("id", 12)
                        put("tag_name", "v1")
                    },
                )
            }
        }
    }

    private suspend fun read(request: GitHubRequest): GitHubResponse {
        val body = when {
            request.path.endsWith("/pulls/1") -> {
                val pull = Json.parseToJsonElement(
                    """{
                      "id":1,"updated_at":"2026-10-18T00:00:00Z","number":1,
                      "head":{"sha":"${DECISION_HEAD.value}","ref":"feature"},"base":{"ref":"master"},
                      "state":"open","draft":false,"body":"","merged":$isMerged,
                      "merge_commit_sha":"${"b".repeat(40)}"
                    }""",
                )
                afterPreflight()
                pull
            }

            request.path.contains("/git/ref/tags/") -> buildJsonObject {
                put(
                    "object",
                    buildJsonObject {
                        put("type", "commit")
                        put("sha", DECISION_HEAD.value)
                    },
                )
            }

            else -> buildJsonObject { put("sha", DECISION_HEAD.value) }
        }
        return GitHubResponse(200, body = body)
    }
}
