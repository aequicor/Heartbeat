package io.aequicor.heartbeat.feature.harness.api.github

import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    fun `same timestamp head conflict invalidates once and late delivery cannot restore verification`() {
        val pull = GitHubEntity.Pull("1", earlier, 1, sha, "master", "work", "", "open", true)
        val original = GitHubObservation(pull, verifiedHead = sha)
        val changed = pull.copy(head = SubjectSha("b".repeat(40)))
        val ambiguous = original.accept(changed)
        assertTrue(ambiguous.isAmbiguous)
        assertEquals(1, ambiguous.inputRevision)
        assertEquals(pull, ambiguous.entity)
        assertNull(ambiguous.current)
        assertNull(ambiguous.verifiedHead)
        assertEquals(ambiguous, ambiguous.accept(pull))
        assertEquals(ambiguous, ambiguous.accept(changed))
        assertEquals(ambiguous, ambiguous.accept(changed.copy(updatedAt = later)))
        assertFailsWith<IllegalArgumentException> { ambiguous.copy(verifiedHead = sha) }
    }

    @Test
    fun `same timestamp issue edit invalidates once until authoritative refresh`() {
        val issue = GitHubEntity.Issue("1", earlier, 1, GitHubNodeId("I_1"), "title", "first", "open")
        val edit = issue.copy(body = "second")
        val ambiguous = GitHubObservation(issue).accept(edit)
        assertEquals(1, ambiguous.inputRevision)
        assertEquals(ambiguous, ambiguous.accept(edit))
        assertEquals(ambiguous, ambiguous.accept(issue))
        val refreshed = ambiguous.refresh(edit)
        assertFalse(refreshed.isAmbiguous)
        assertEquals(edit, refreshed.current)
        assertEquals(1, refreshed.inputRevision)
        assertEquals(refreshed, refreshed.refresh(edit))
    }

    @Test
    fun `dismissal with unchanged submission time makes review evidence unavailable`() {
        val approved = GitHubEntity.Review("1", earlier, sha, "APPROVED")
        val dismissed = approved.copy(state = "DISMISSED")
        val ambiguous = GitHubObservation(approved).accept(dismissed)
        assertNull(ambiguous.current)
        assertEquals(1, ambiguous.inputRevision)
        assertEquals(ambiguous, ambiguous.accept(approved))
        assertEquals(dismissed, ambiguous.refresh(dismissed).current)
    }

    @Test
    fun `ambiguity survives serialization and legacy observation remains usable`() {
        val issue = GitHubEntity.Issue("1", earlier, 1, GitHubNodeId("I_1"), "title", "first", "open")
        val original = GitHubObservation(issue)
        val legacy = Json.encodeToString(original)
        assertFalse("isAmbiguous" in legacy)
        assertEquals(issue, Json.decodeFromString<GitHubObservation>(legacy).current)
        val ambiguous = original.accept(issue.copy(body = "second"))
        val restored = Json.decodeFromString<GitHubObservation>(Json.encodeToString(ambiguous))
        assertEquals(ambiguous, restored)
        assertNull(restored.current)
        assertEquals(restored, restored.accept(issue))
    }

    @Test
    fun `authoritative refresh accepts equal and newer head but never restores verification`() {
        val pull = GitHubEntity.Pull("1", earlier, 1, sha, "master", "work", "", "open", true)
        val changed = pull.copy(head = SubjectSha("b".repeat(40)))
        val original = GitHubObservation(pull, verifiedHead = sha)
        val refreshed = original.refresh(changed)
        assertEquals(changed, refreshed.current)
        assertEquals(1, refreshed.inputRevision)
        assertNull(refreshed.verifiedHead)
        val ambiguous = original.accept(changed)
        assertEquals(refreshed, ambiguous.refresh(changed))
        val newer = ambiguous.refresh(changed.copy(updatedAt = later))
        assertFalse(newer.isAmbiguous)
        assertEquals(1, newer.inputRevision)
        assertNull(newer.verifiedHead)
        assertEquals(newer, newer.refresh(pull))
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
