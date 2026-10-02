package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeEngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** [ClaudeEngineManager] over the process transport, which knows how this host finds and starts the CLI. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class JvmClaudeManager(
    private val transport: ClaudeTransport,
    private val signIn: ClaudeSignIn,
    private val dispatchers: DispatcherProvider,
) : ClaudeEngineManager {
    private val log = Log.tag("ClaudeManager")

    override suspend fun inspect(launch: LaunchContext): Installation = transport.locate(launch)

    override suspend fun loginStatus(launch: LaunchContext): LoginState = signIn.status(launch)

    /** Claude Code has one browser sign-in; a code the page shows instead is pasted back. */
    override suspend fun login(launch: LaunchContext, method: LoginMethod, session: LoginSession): LoginState =
        signIn.login(launch, session)

    override suspend fun logout(launch: LaunchContext): LoginState = signIn.logout(launch)

    override suspend fun check(settings: LaunchSettings): List<LaunchProblem> =
        withContext(dispatchers.io) { claudeLaunchProblems(settings) }

    override suspend fun resolveRelease(feeds: ReleaseFeeds): InstallPlan {
        val platform = claudeReleasePlatform() ?: run {
            log.w { "Claude Code publishes no build for this host" }
            installFailed(InstallFailureReason.NoAssetForPlatform)
        }
        return claudeInstallPlan(feeds, platform)
    }
}

/**
 * The native build of the `stable` channel for [platform], as the official installer finds it: the channel names
 * the version, the version's `manifest.json` lists each platform's file, SHA-256 and size.
 */
internal suspend fun claudeInstallPlan(feeds: ReleaseFeeds, platform: String): InstallPlan {
    val version = feeds.document("$CLAUDE_RELEASES/stable", ClaudeDownloadHosts).trim()
    if (!ReleaseVersion.matches(version)) installFailed(InstallFailureReason.NoRelease)
    val manifest = manifest(feeds.document("$CLAUDE_RELEASES/$version/manifest.json", ClaudeDownloadHosts))
    if (manifest.text("version") != version) installFailed(InstallFailureReason.UntrustedSource)
    val build = (manifest["platforms"] as? JsonObject)?.get(platform) as? JsonObject
        ?: installFailed(InstallFailureReason.NoAssetForPlatform)
    val binary = build.text("binary")?.takeIf { it in ClaudeBinaries }
        ?: installFailed(InstallFailureReason.UntrustedSource)
    val checksum = build.text("checksum")?.takeIf { Sha256.matches(it) }
        ?: installFailed(InstallFailureReason.UntrustedSource)
    return InstallPlan(
        version = version,
        url = "$CLAUDE_RELEASES/$version/$platform/$binary",
        sha256 = checksum,
        size = (build["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
        archive = ArchiveKind.Raw(binary),
        executable = binary,
        allowedHosts = ClaudeDownloadHosts,
    )
}

private fun manifest(document: String): JsonObject {
    val manifest = try {
        Json.parseToJsonElement(document)
    } catch (e: SerializationException) {
        Log.tag("ClaudeManager").w(e) { "Claude Code manifest is not JSON" }
        installFailed(InstallFailureReason.NoRelease)
    }
    return manifest as? JsonObject ?: installFailed(InstallFailureReason.NoRelease)
}

private fun installFailed(reason: InstallFailureReason): Nothing =
    throw ManagementException(ManagementFailure.Install(reason))

private const val CLAUDE_RELEASES = "https://downloads.claude.ai/claude-code-releases"
private val ClaudeDownloadHosts = setOf("downloads.claude.ai")
private val ClaudeBinaries = setOf("claude", "claude.exe")
private val ReleaseVersion = Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""")
private val Sha256 = Regex("[0-9a-f]{64}")
