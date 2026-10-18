package io.aequicor.heartbeat.feature.worktreemode.impl.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Captures a commit over HEAD through a private index copy without changing the branch or the agent's index. */
internal suspend fun commitCheckoutFiles(
    message: String,
    git: suspend (List<String>, Map<String, String>) -> String,
): String {
    val gitDirectory = Path.of(git(listOf("rev-parse", "--absolute-git-dir"), emptyMap()))
    val index = Files.createTempFile("heartbeat-snapshot-", ".index")
    try {
        val current = gitDirectory.resolve("index")
        if (Files.isRegularFile(current)) {
            Files.copy(current, index, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.delete(index)
        }
        val environment = mapOf("GIT_INDEX_FILE" to index.toString()) + SNAPSHOT_IDENTITY
        git(listOf("add", "--all"), environment)
        val tree = git(listOf("write-tree"), environment)
        val head = git(listOf("rev-parse", "--verify", "HEAD^{commit}"), emptyMap())
        return git(listOf("commit-tree", tree, "-p", head, "-m", message), environment)
    } finally {
        Files.deleteIfExists(index)
    }
}

/** Private build inputs never depend on the configured user identity. */
private val SNAPSHOT_IDENTITY = mapOf(
    "GIT_AUTHOR_NAME" to "Heartbeat",
    "GIT_AUTHOR_EMAIL" to "sandbox@heartbeat.invalid",
    "GIT_COMMITTER_NAME" to "Heartbeat",
    "GIT_COMMITTER_EMAIL" to "sandbox@heartbeat.invalid",
)
