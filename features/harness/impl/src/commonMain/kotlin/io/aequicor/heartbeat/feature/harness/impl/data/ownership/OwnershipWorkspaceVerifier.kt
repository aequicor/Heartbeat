package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandRequest
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands
import kotlin.time.Duration.Companion.seconds

/** Independently resolves the actual working copy. Credentials/URLs/output never enter logs or manifests. */
@Inject
internal class OwnershipWorkspaceVerifier(private val commands: LocalCommands) {
    private val log = Log.tag("OwnershipWorkspace")

    suspend fun head(workspace: WorkspaceRef, identity: GitHubTaskIdentity): SubjectSha? {
        log.v { "verify ownership working copy" }
        val remote = read(workspace, listOf("config", "--get", "remote.origin.url"))
        val expected = "${identity.repository.owner}/${identity.repository.name}".lowercase()
        val actual = remote?.let { (HTTPS.matchEntire(it) ?: SSH.matchEntire(it))?.groupValues?.get(1)?.lowercase() }
        val head = read(workspace, listOf("rev-parse", "--verify", "HEAD"))
        return if (actual == expected && head != null && SHA.matches(head)) SubjectSha(head) else null
    }

    private suspend fun read(workspace: WorkspaceRef, arguments: List<String>): String? {
        val outcome = commands.run(LocalCommandRequest(workspace, "git", arguments, 10.seconds))
        return (outcome as? LocalCommandOutcome.Exited)?.takeIf { it.exitCode == 0 }?.output?.trim()
    }
}

private val HTTPS = Regex("""https://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?""")
private val SSH = Regex("""git@github\.com:([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?""")
private val SHA = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
