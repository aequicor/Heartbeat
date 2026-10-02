package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubDownloadHosts
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.isTrustedReleaseUrl
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.sha256FromSums
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The Pi a process starts: [executable] (null when none is attached) and where it came from, plus user entries. */
internal data class PiStartup(
    val executable: Path?,
    val source: InstallSource,
    val environment: List<EnvironmentEntry> = emptyList(),
) {
    override fun toString(): String = "PiStartup(source=$source)"
}

/**
 * The custom executable of [context]'s settings, else Heartbeat's newer copy, else the [bundled] Pi. A custom path the
 * file system cannot name (Windows reserved characters) is a custom Pi that cannot start.
 */
internal fun resolvePiStartup(context: LaunchContext, bundled: Path?): PiStartup {
    val custom = context.settings.executable?.takeIf { it.isNotBlank() }
    val managed = context.managed?.executable
    val environment = context.settings.environment
    return when {
        custom != null -> PiStartup(pathOrNull(custom), InstallSource.Custom, environment)
        managed != null -> PiStartup(pathOrNull(managed), InstallSource.Managed, environment)
        bundled != null -> PiStartup(bundled, InstallSource.Bundled, environment)
        else -> PiStartup(null, InstallSource.Missing, environment)
    }
}

/** The user's entries on top of the retained host environment; Heartbeat's own variables are set after them. */
internal fun applyPiEnvironment(environment: MutableMap<String, String>, startup: PiStartup) {
    retainPiEnvironment(environment)
    startup.environment.forEach { environment[it.name] = it.value }
}

/** File checks of launch settings; they never block saving, the panel shows them. */
internal fun piLaunchProblems(settings: LaunchSettings): List<LaunchProblem> {
    val path = settings.executable?.takeIf { it.isNotBlank() } ?: return emptyList()
    val file = File(path)
    return when {
        !file.exists() -> listOf(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotFound))

        !(file.isFile && file.canExecute()) -> listOf(
            LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotExecutable),
        )

        else -> emptyList()
    }
}

/** The release build of this host (`darwin-arm64`, `windows-x64`…), or null where Pi publishes none we run. */
internal fun piReleaseTarget(
    osName: String = System.getProperty("os.name").orEmpty(),
    osArch: String = System.getProperty("os.arch").orEmpty(),
): String? {
    val arch = when (osArch.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> return null
    }
    return when {
        osName.startsWith("Mac") -> "darwin-$arch"
        osName.startsWith("Windows") -> "windows-$arch"
        else -> null
    }
}

/**
 * The official archive of [release] for [target], as the bundled one is packaged: `.tar.gz` wraps everything in one
 * directory (stripped), the Windows `.zip` is flat. Its SHA-256 comes from GitHub's digest, else from the release's
 * `SHA256SUMS`; an archive without either is untrusted.
 */
internal suspend fun piInstallPlan(release: GitHubRelease, target: String, feeds: ReleaseFeeds): InstallPlan {
    val isWindows = target.startsWith("windows")
    val name = "pi-$target." + if (isWindows) "zip" else "tar.gz"
    val asset = release.asset(name) ?: installFailed(InstallFailureReason.NoAssetForPlatform)
    // A publisher's URL outside its download hosts is refused before it reaches the plan.
    if (!isTrustedReleaseUrl(asset.url, GitHubDownloadHosts)) installFailed(InstallFailureReason.UntrustedSource)
    val sha256 = asset.sha256 ?: release.asset(PI_SUMS)?.let { sums ->
        sha256FromSums(feeds.document(sums.url, GitHubDownloadHosts), name)
    } ?: installFailed(InstallFailureReason.UntrustedSource)
    return InstallPlan(
        version = release.tag.removePrefix("v"),
        url = asset.url,
        sha256 = sha256,
        size = asset.size.takeIf { it > 0 },
        archive = if (isWindows) ArchiveKind.Zip() else ArchiveKind.TarGz(stripComponents = 1),
        executable = if (isWindows) "pi.exe" else "pi",
        allowedHosts = GitHubDownloadHosts,
    )
}

/** Only the version Heartbeat ships is verified; any other is a release Heartbeat was not tested with. */
internal fun piCompatibility(version: String, bundled: String?): Compatibility = when (bundled) {
    null -> Compatibility.Unknown
    version -> Compatibility.Verified
    else -> Compatibility.Unverified
}

/** This failure without its message, which names the user's paths; only the kind of failure is logged. */
internal fun Throwable.withoutDetails(): Throwable = IllegalStateException(this::class.simpleName ?: "Failure")

private fun pathOrNull(path: String): Path? = try {
    Path.of(path)
} catch (e: InvalidPathException) {
    Log.tag("PiStartup").w(e.withoutDetails()) { "custom Pi path cannot be used" }
    null
}

/** The version `pi --version` prints, or null. */
internal fun parsePiVersion(output: String): String? = VersionPattern.find(output)?.groupValues?.get(1)

private fun installFailed(reason: InstallFailureReason): Nothing =
    throw ManagementException(ManagementFailure.Install(reason))

internal const val PI_OWNER = "earendil-works"
internal const val PI_REPOSITORY = "pi"
private const val PI_SUMS = "SHA256SUMS"
private val VersionPattern = Regex("""(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)""")
