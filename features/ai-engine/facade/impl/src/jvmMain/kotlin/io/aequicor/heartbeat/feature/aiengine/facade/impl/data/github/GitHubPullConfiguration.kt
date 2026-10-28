package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import okio.ByteString.Companion.encodeUtf8
import java.io.File

/**
 * Host-only opt-in. The desktop launcher pins a public repository and an existing GitHub CLI account.
 * Credential material stays in the host; neither project files nor model arguments configure its source.
 */
internal class GitHubPullConfiguration(val repository: String, private val token: String, val account: String) {
    init {
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}").matches(repository))
        require(
            token.isNotBlank() && token.length <= MAX_TOKEN_CHARS &&
                token.none { it.isWhitespace() || it.isISOControl() },
        )
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}").matches(account))
    }

    val binding: String get() = (repository + "\u0000" + account + "\u0000" + token).encodeUtf8().sha256().hex()

    fun authorization(): String = "Bearer $token"

    override fun toString(): String = "GitHubPullConfiguration(***)"
}

/** Separate from transport so tests never read the environment or real credentials. */
internal fun interface GitHubPullConfigurationSource {
    suspend fun current(workspace: WorkspaceRef): GitHubPullConfiguration?

    /**
     * Cheap, side-effect-free check that the host is set up at all. The tools are not offered to the model while
     * it is false, so an unconfigured desktop never advertises operations that can only fail.
     */
    fun isAvailable(): Boolean = true
}

@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopGitHubPullConfiguration(private val credentials: GitHubPullCredentials) :
    GitHubPullConfigurationSource {
    override fun isAvailable(): Boolean = githubHostIsConfigured(System::getenv)

    override suspend fun current(workspace: WorkspaceRef): GitHubPullConfiguration? {
        val repository = System.getenv("HEARTBEAT_GITHUB_REPOSITORY") ?: return null
        val account = System.getenv("HEARTBEAT_GITHUB_ACCOUNT") ?: return null
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}").matches(account)) { "Invalid GitHub account" }
        return GitHubPullConfiguration(repository, credentials.read(account, workspace), account)
    }
}

/** Repository, account and an absolute `gh` executable are all pinned by the operator; no credential is read. */
internal fun githubHostIsConfigured(env: (String) -> String?): Boolean {
    val cli = env("HEARTBEAT_GITHUB_CLI")?.let(::File)
    return !env("HEARTBEAT_GITHUB_REPOSITORY").isNullOrBlank() &&
        !env("HEARTBEAT_GITHUB_ACCOUNT").isNullOrBlank() &&
        cli != null && cli.isAbsolute && cli.isFile && cli.canExecute()
}

private const val MAX_TOKEN_CHARS = 4096

internal val GitHubPullToolsEnabled = FeatureToggle.Flag(
    key = "ai.github_pull_tools",
    description = "GitHub PR: проверяемые операции с метаданными публичного репозитория (Desktop)",
)
