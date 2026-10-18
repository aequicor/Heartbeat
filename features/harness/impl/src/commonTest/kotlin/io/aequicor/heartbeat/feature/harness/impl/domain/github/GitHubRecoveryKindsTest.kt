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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GitHubRecoveryKindsTest {
    @Test
    fun `lost issue pull and comment responses reconcile the exact marked object`() = runTest {
        val head = SubjectSha("a".repeat(40))
        val repository = GitHubRepository("owner", "repo")
        val process = ProcessId("work")
        val key = WriteKey("create")
        for (change in listOf(
            GitHubChange.CreateIssue("title", "body"),
            GitHubChange.Comment(1, "body"),
            GitHubChange.CreatePull("title", "body", "work", "master", head),
        )) {
            val env = ProcessesTestEnv(this)
            env.processes.open(process)
            env.processes.apply(process, ProcessCommand.Grant(ProcessAuthority.Write))
            var entity: JsonObject? = null
            var posts = 0
            val transport = FakeGitHubTransport { request ->
                when {
                    request.path.contains(
                        "/commits/",
                    ) -> GitHubResponse(200, buildJsonObject { put("sha", head.value) })

                    request.method == "GET" -> GitHubResponse(200, JsonArray(listOfNotNull(entity)))

                    else -> {
                        posts++
                        entity = buildJsonObject {
                            put("id", 1)
                            put("number", 1)
                            put("node_id", "I_1")
                            put("title", "title")
                            put("body", request.body!!.jsonObject.getValue("body"))
                            put("updated_at", "2026-10-18T12:00:00Z")
                            put("state", "open")
                            put("draft", true)
                            put(
                                "head",
                                buildJsonObject {
                                    put("sha", head.value)
                                    put("ref", "work")
                                },
                            )
                            put("base", buildJsonObject { put("ref", "master") })
                        }
                        GitHubResponse(0)
                    }
                }
            }
            fun service() = HarnessScriptGitHub(
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
            assertEquals(GitHubWriteResult.Unknown, service().write(process, 0, key, repository, change))
            assertIs<GitHubWriteResult.Confirmed>(service().reconcile(process, key))
            assertIs<GitHubWriteResult.Confirmed>(service().write(process, 0, key, repository, change))
            assertEquals(1, posts)
        }
    }

    @Test
    fun `full permanent registry refuses a new key rather than evicting old operations`() = runTest {
        val env = ProcessesTestEnv(this)
        val process = ProcessId("work")
        val repository = GitHubRepository("owner", "repo")
        val record = env.processes.open(process)
        env.processes.apply(process, ProcessCommand.Grant(ProcessAuthority.Write))
        val registry = GitHubRegistry(env.data)
        val change = GitHubChange.Comment(1, "")
        repeat(128) { index ->
            assertIs<GitHubReservation.Reserved>(
                registry.reserve(
                    GitHubOperation(process, record.incarnation, 0, WriteKey("comment_$index"), repository, change),
                ),
            )
        }
        val transport = FakeGitHubTransport { error("Quota must refuse before external IO") }
        val service = HarnessScriptGitHub(
            env.access,
            CommandOrigins(),
            env.processes,
            registry,
            GitHubHost(
                GitHubApi(transport, env.clock),
                GitHubHostAuthority { false },
                { true },
            ),
            env.processGate,
        )
        assertEquals(
            GitHubWriteResult.Refused(GitHubWriteRefusal.Quota),
            service.write(process, 0, WriteKey("extra"), repository, change),
        )
        assertIs<GitHubReservation.Existing>(
            registry.reserve(
                GitHubOperation(process, record.incarnation, 0, WriteKey("comment_0"), repository, change),
            ),
        )
    }
}
