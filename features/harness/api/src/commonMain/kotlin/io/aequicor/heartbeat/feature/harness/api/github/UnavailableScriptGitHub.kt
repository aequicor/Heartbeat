package io.aequicor.heartbeat.feature.harness.api.github

import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey

/** Compatibility service for probe hosts without external IO; never performs reads or writes. */
public object UnavailableScriptGitHub : ScriptGitHub {
    override suspend fun read(repository: GitHubRepository, query: GitHubQuery): GitHubReadResult =
        GitHubReadResult.Failed(GitHubFailure.Unsupported)

    override suspend fun write(
        process: ProcessId,
        inputRevision: Long,
        key: WriteKey,
        repository: GitHubRepository,
        change: GitHubChange,
    ): GitHubWriteResult = GitHubWriteResult.Refused(GitHubWriteRefusal.Unavailable)

    override suspend fun reconcile(process: ProcessId, key: WriteKey): GitHubWriteResult =
        GitHubWriteResult.Refused(GitHubWriteRefusal.Unavailable)
}
