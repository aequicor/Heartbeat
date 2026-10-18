package io.aequicor.heartbeat.feature.harness.api.github

import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class GitHubEvidenceTest {
    private val sha = SubjectSha("a".repeat(40))
    private val time = Instant.fromEpochSeconds(1)

    @Test
    fun `run evidence requires exact workflow head event and complete owned artifacts`() {
        val run = GitHubEntity.Run("1", time, 2, sha, "workflow_dispatch", "completed", "success", "title")
        val artifact = GitHubEntity.Artifact("3", time, "installer", false, "1")
        fun valid(value: GitHubEntity.Run = run, item: GitHubEntity.Artifact = artifact, complete: Boolean = true) =
            GitHubEvidence.run(value, GitHubRunExpectation(2, sha, setOf("installer")), listOf(item), complete)
        assertTrue(valid())
        assertFalse(valid(run.copy(head = SubjectSha("b".repeat(40)))))
        assertFalse(valid(run.copy(workflow = 3)))
        assertFalse(valid(run.copy(conclusion = "failure")))
        assertFalse(valid(run.copy(status = "in_progress")))
        assertFalse(valid(run.copy(event = "push")))
        assertFalse(valid(item = artifact.copy(run = "other")))
        assertFalse(valid(item = artifact.copy(isExpired = true)))
        assertFalse(valid(complete = false))
    }

    @Test
    fun `release requires peeled tag commit published state and expected assets`() {
        val release = GitHubEntity.Release("1", time, "v1", "master", "body", false, listOf("installer"))
        assertTrue(GitHubEvidence.release(release, "v1", sha, sha, setOf("installer")))
        assertFalse(GitHubEvidence.release(release, "v1", null, sha, setOf("installer")))
        assertFalse(GitHubEvidence.release(release.copy(isDraft = true), "v1", sha, sha, setOf("installer")))
        assertFalse(GitHubEvidence.release(release, "v1", sha, sha, setOf("missing")))
    }
}
