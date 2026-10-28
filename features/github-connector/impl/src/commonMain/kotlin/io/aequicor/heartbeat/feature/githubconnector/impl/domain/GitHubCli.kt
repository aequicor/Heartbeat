package io.aequicor.heartbeat.feature.githubconnector.impl.domain

import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnection
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorFailure
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubRepository

/** A failure of a connector step with a reason the screen can explain; carries no CLI output. */
internal class GitHubCliException(val failure: GitHubConnectorFailure) : Exception("GitHub CLI step failed: $failure")

/** What the account may do in the chosen repository. */
internal enum class GitHubAccess { Granted, NoAccess, NoPullRequestAccess }

/** The user's GitHub CLI as the connector sees it; tokens never leave the implementation. */
internal interface GitHubCli {
    /** The absolute path of the CLI, preferring [preferred], or `null` when it is not installed. */
    suspend fun locate(preferred: String?): String?

    /** Logged-in github.com accounts of [cli]. */
    suspend fun accounts(cli: String): List<String>

    /** Repositories [account] can push to, most recently pushed first. */
    suspend fun repositories(cli: String, account: String): List<GitHubRepository>

    /** What [account] may do in [repository]. */
    suspend fun access(cli: String, account: String, repository: String): GitHubAccess
}

/** The saved connection of the active profile. */
internal interface GitHubConnectionStore {
    /** The saved connection, or `null`. */
    suspend fun load(): GitHubConnection?

    /** Replaces the saved connection. */
    suspend fun save(connection: GitHubConnection)

    /** Deletes the saved connection. */
    suspend fun clear()
}
