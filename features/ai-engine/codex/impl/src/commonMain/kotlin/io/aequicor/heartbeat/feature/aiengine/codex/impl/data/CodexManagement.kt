package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubDownloadHosts
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.sha256FromSums

/** Qualified manager contract, like [CodexEngineFactory], so it never collides with other engines' managers. */
public interface CodexEngineManager : EngineManager

/**
 * What engine management may change for Codex: Heartbeat installs its own copy of the official package, the CLI
 * signs in to ChatGPT in the browser or with a device code, and launch settings may name the executable,
 * `CODEX_HOME`, `-c` overrides and extra environment. Keys Heartbeat sets to
 * isolate sessions (provider, features, tools, approvals, sandbox, login method) are reserved.
 */
internal val CodexManagementSpec = ManagementSpec(
    install = InstallSupport.Managed,
    login = LoginSupport.CliWithDeviceCode,
    launch = LaunchSpec(
        options = LaunchOption.entries.toSet(),
        homeVariable = "CODEX_HOME",
        reservedEnvironment = setOf("CODEX_HOME"),
        reservedConfigKeys = setOf(
            "model_provider", "model_providers", "features", "mcp_servers", "web_search", "tools", "notify",
            "hooks", "profile", "profiles", "forced_login_method", "chatgpt_base_url", "approval_policy",
            "sandbox_mode", "sandbox_workspace_write", "cli_auth_credentials_store",
        ),
    ),
)

/** A Codex release build: the target triple of its package and the executable inside it. */
internal data class CodexTarget(val triple: String, val executable: String)

/** [CodexEngineManager] over the transport, which knows how this host finds and starts Codex. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class CodexManager(private val transport: CodexTransport, private val logins: CodexLogin) :
    CodexEngineManager {
    private val log = Log.tag("CodexManager")

    override suspend fun inspect(launch: LaunchContext): Installation = transport.locate(launch)

    override suspend fun loginStatus(launch: LaunchContext): LoginState = logins.status(launch)

    override suspend fun login(launch: LaunchContext, method: LoginMethod, session: LoginSession): LoginState =
        logins.login(launch, method, session)

    override suspend fun logout(launch: LaunchContext): LoginState = logins.logout(launch)

    override suspend fun check(settings: LaunchSettings): List<LaunchProblem> = transport.check(settings)

    override suspend fun resolveRelease(feeds: ReleaseFeeds): InstallPlan {
        val target = transport.releaseTarget() ?: run {
            log.w { "Codex publishes no build for this host" }
            throw installFailure(InstallFailureReason.NoAssetForPlatform)
        }
        return codexInstallPlan(feeds.latestGitHubRelease(CODEX_OWNER, CODEX_REPOSITORY), target, feeds)
    }
}

/**
 * The official package `codex-package-<triple>.tar.gz` of [release] for [target], unpacked as is. Its SHA-256 comes
 * from GitHub's digest, else from the release's `codex-package_SHA256SUMS`; a package without either is untrusted.
 */
internal suspend fun codexInstallPlan(release: GitHubRelease, target: CodexTarget, feeds: ReleaseFeeds): InstallPlan {
    val name = "codex-package-${target.triple}.tar.gz"
    val asset = release.asset(name) ?: throw installFailure(InstallFailureReason.NoAssetForPlatform)
    val sha256 = asset.sha256 ?: release.asset(CODEX_SUMS)?.let { sums ->
        sha256FromSums(feeds.document(sums.url, GitHubDownloadHosts), name)
    } ?: throw installFailure(InstallFailureReason.UntrustedSource)
    return InstallPlan(
        version = release.tag.removePrefix("rust-v").removePrefix("v"),
        url = asset.url,
        sha256 = sha256,
        size = asset.size.takeIf { it > 0 },
        archive = ArchiveKind.TarGz(),
        executable = target.executable,
        allowedHosts = GitHubDownloadHosts,
    )
}

private fun installFailure(reason: InstallFailureReason) = ManagementException(ManagementFailure.Install(reason))

private const val CODEX_OWNER = "openai"
private const val CODEX_REPOSITORY = "codex"
private const val CODEX_SUMS = "codex-package_SHA256SUMS"
