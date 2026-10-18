package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubFailure
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubQuery
import io.aequicor.heartbeat.feature.harness.api.github.GitHubReadResult
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val REPO = GitHubRepository("owner", "repo")
private val SHA = SubjectSha("a".repeat(40))

class GitHubApiTest {
    @Test
    fun `rate limit and offline backoff prevent extra requests before retry time`() = runTest {
        val clock = ProcessesTestClock(1000)
        val transport = FakeGitHubTransport { GitHubResponse(429, headers = mapOf("retry-after" to "120")) }
        val api = GitHubApi(transport, clock)
        val failed = assertIs<GitHubReadResult.Failed>(api.read(REPO, GitHubQuery.Issues()))
        assertEquals(GitHubFailure.RateLimited, failed.reason)
        assertEquals(clock.now() + 120.seconds, failed.retryAt)
        api.read(REPO, GitHubQuery.Issues())
        assertEquals(1, transport.calls.size)
        clock.seconds += 120
        transport.handler = { GitHubResponse(0) }
        assertIs<GitHubReadResult.Failed>(api.read(REPO, GitHubQuery.Issues()))
        api.read(REPO, GitHubQuery.Issues())
        assertEquals(2, transport.calls.size)
        clock.seconds += 100
        transport.handler = { GitHubResponse(200, JsonArray(emptyList())) }
        assertIs<GitHubReadResult.Read>(api.read(REPO, GitHubQuery.Issues()))
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun `filtered issue page still preserves pagination and invalid pages never reach transport`() = runTest {
        val transport = FakeGitHubTransport {
            GitHubResponse(
                200,
                Json.parseToJsonElement("""[{"pull_request":{}}]"""),
                mapOf("link" to "<next>; rel=\"next\""),
            )
        }
        val api = GitHubApi(transport, ProcessesTestClock(1000))
        val page = assertIs<GitHubReadResult.Read>(api.read(REPO, GitHubQuery.Issues())).snapshot
        assertTrue(page.entries.isEmpty())
        assertEquals(2, page.nextPage)
        assertFailsWith<IllegalArgumentException> { api.read(REPO, GitHubQuery.Issues(21)) }
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `GraphQL cursor is a variable and partial GraphQL response is rejected`() = runTest {
        val transport = FakeGitHubTransport { request ->
            assertEquals("graphql", request.path)
            assertEquals("opaque cursor", request.body!!.jsonObject.obj("variables").text("after"))
            GitHubResponse(200, Json.parseToJsonElement("""{"data":{},"errors":[{"message":"private"}]}"""))
        }
        val result = GitHubApi(transport, ProcessesTestClock(1000)).read(
            REPO,
            GitHubQuery.ProjectItems(GitHubNodeId("P_1"), "opaque cursor"),
        )
        assertEquals(GitHubReadResult.Failed(GitHubFailure.InvalidResponse), result)
    }

    @Test
    fun `annotated tag is peeled to an exact commit instead of trusting release target branch`() = runTest {
        val transport = FakeGitHubTransport { request ->
            GitHubResponse(
                200,
                buildJsonObject {
                    put(
                        "object",
                        buildJsonObject {
                            put("sha", SHA.value)
                            put("type", if (request.path.contains("git/ref/")) "tag" else "commit")
                        },
                    )
                },
            )
        }
        val result = assertIs<GitHubReadResult.Read>(
            GitHubApi(transport, ProcessesTestClock(1000)).read(REPO, GitHubQuery.Tag("v1")),
        )
        assertEquals(SHA, result.snapshot.commit)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `dispatch success is not confirmed for a different head or workflow`() = runTest {
        val change = GitHubChange.Dispatch(2, "master", SHA)
        val operation = GitHubOperation(ProcessId("work"), "incarnation", 0, WriteKey("dispatch"), REPO, change)
        val transport = FakeGitHubTransport {
            GitHubResponse(
                200,
                Json.parseToJsonElement(
                    """{
                "id":1,"workflow_id":3,"head_sha":"${"b".repeat(40)}","event":"workflow_dispatch",
                "status":"completed","conclusion":"success","display_title":"test","updated_at":"2026-10-18T12:00:00Z"
            }""",
                ),
            )
        }
        val result = GitHubReconciliation(GitHubApi(transport, ProcessesTestClock(1000))).response(
            operation,
            GitHubResponse(200, Json.parseToJsonElement("""{"workflow_run_id":1}""")),
        )
        assertNull(result)
    }

    @Test
    fun `incomplete or ambiguous marker scans remain unknown`() = runTest {
        val change = GitHubChange.Comment(1, "body")
        val operation = GitHubOperation(ProcessId("work"), "incarnation", 0, WriteKey("comment"), REPO, change)
        val comment = buildJsonObject {
            put("id", 1)
            put("body", change.body.marked(operation.marker))
            put("updated_at", "2026-10-18T12:00:00Z")
        }
        val transport = FakeGitHubTransport {
            GitHubResponse(200, JsonArray(listOf(comment)), mapOf("link" to "<next>; rel=\"next\""))
        }
        val reconciliation = GitHubReconciliation(GitHubApi(transport, ProcessesTestClock(1000)))
        assertNull(reconciliation.find(operation))
        transport.handler = { GitHubResponse(200, JsonArray(listOf(comment, comment))) }
        assertNull(reconciliation.find(operation))
    }
}

internal class FakeGitHubTransport(var handler: suspend (GitHubRequest) -> GitHubResponse) : GitHubTransport {
    override val isAvailable = true
    val calls = mutableListOf<GitHubRequest>()
    override suspend fun call(request: GitHubRequest): GitHubResponse {
        calls += request
        return handler(request)
    }
}
