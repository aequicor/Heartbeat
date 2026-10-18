package io.aequicor.heartbeat.feature.harness.api.github

import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

class GitHubObservationTest {
    private val sha = SubjectSha("a".repeat(40))
    private val later = Instant.fromEpochSeconds(200)
    private val earlier = Instant.fromEpochSeconds(100)

    @Test
    fun `issue edit advances input but duplicate and late deliveries do not`() {
        val issue = GitHubEntity.Issue("1", earlier, 1, GitHubNodeId("I_1"), "title", "first", "open")
        val original = GitHubObservation(issue)
        val edit = issue.copy(updatedAt = later, body = "second")
        val observed = original.accept(edit)
        assertEquals(1, observed.inputRevision)
        assertEquals(observed, observed.accept(issue))
        assertEquals(observed, observed.accept(edit))
    }

    @Test
    fun `new head invalidates exact verification and old head cannot restore it`() {
        val pull = GitHubEntity.Pull("1", earlier, 1, sha, "master", "work", "", "open", true)
        val original = GitHubObservation(pull, verifiedHead = sha)
        val next = original.accept(pull.copy(updatedAt = later, head = SubjectSha("b".repeat(40))))
        assertEquals(1, next.inputRevision)
        assertNull(next.verifiedHead)
        assertEquals(next, next.accept(pull))
        assertFailsWith<IllegalArgumentException> { next.copy(verifiedHead = sha) }
    }

    @Test
    fun `write serialization binds revision relevant payload and head`() {
        val change: GitHubChange = GitHubChange.CreatePull("title", "body", "work", "master", sha)
        assertEquals(change, Json.decodeFromString<GitHubChange>(Json.encodeToString(change)))
        assertFailsWith<IllegalArgumentException> { GitHubRepository("owner", "../repo") }
        assertFailsWith<IllegalArgumentException> { GitHubRepository("--hostname", "..") }
        assertFailsWith<IllegalArgumentException> { GitHubNodeId("node\nmutation") }
    }

    @Test
    fun `diagnostics hide write bodies workflow inputs and private query references`() {
        val secret = "private_payload_value"
        val changes = listOf(
            GitHubChange.CreateIssue(secret, secret),
            GitHubChange.Comment(1, secret),
            GitHubChange.Dispatch(1, "master", sha, mapOf("input" to secret)),
        )
        changes.forEach { assertFalse(secret in it.toString()) }
        assertFalse(secret in GitHubQuery.Tag(secret).toString())
        assertFalse(secret in GitHubRunExpectation(1, sha, setOf(secret)).toString())
    }
}
