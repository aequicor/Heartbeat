package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * [PiEngineManager] of the desktop: the bundled Pi is the verified one, a newer official release is installed as
 * Heartbeat's own copy and reported as unverified until a Heartbeat build ships that version.
 */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class DesktopPiManager(private val processes: PiProcessLauncher, private val dispatchers: DispatcherProvider) :
    PiEngineManager {
    private val log = Log.tag("PiManager")

    /** The bundled version last read; [compatibility] compares against it. */
    @Volatile
    private var bundled: String? = null

    override suspend fun inspect(launch: LaunchContext): Installation = withContext(dispatchers.io) {
        val startup = processes.startup(launch)
        val executable = startup.executable
        val isPresent = executable != null && Files.isRegularFile(executable) && Files.isExecutable(executable)
        val isRunnable = isPresent || isPiPendingDownload(startup.source, executable, processes.canPrepare())
        val version = when {
            executable == null || !isPresent -> null
            startup.source == InstallSource.Bundled -> processes.bundledVersion() ?: readPiRuntimeVersion(startup)
            else -> startup.runtimeVersion ?: readPiRuntimeVersion(startup)
        }
        log.i { "Pi located source=${startup.source} version=${version ?: "unknown"}" }
        Installation(startup.source, version, executable?.toString(), isRunnable)
    }

    override suspend fun check(settings: LaunchSettings): List<LaunchProblem> =
        withContext(dispatchers.io) { piLaunchProblems(settings) }

    override suspend fun bundledVersion(): String? =
        withContext(dispatchers.io) { processes.bundledVersion() }.also { bundled = it }

    override fun compatibility(version: String): Compatibility = piCompatibility(version, bundled)

    override suspend fun resolveRelease(feeds: ReleaseFeeds): InstallPlan {
        val target = piReleaseTarget() ?: run {
            log.w { "Pi publishes no build for this host" }
            throw ManagementException(ManagementFailure.Install(InstallFailureReason.NoAssetForPlatform))
        }
        return piInstallPlan(feeds.latestGitHubRelease(PI_OWNER, PI_REPOSITORY), target, feeds)
    }
}

/**
 * Whether the pinned Pi is simply not downloaded yet: the bundled source without a file on disk, which the first use
 * prepares, is not a launch Heartbeat refuses as unsafe. A custom or managed executable that is absent stays
 * unrunnable.
 */
internal fun isPiPendingDownload(source: InstallSource, executable: Path?, isPreparable: Boolean): Boolean =
    isPreparable && source == InstallSource.Bundled && (executable == null || !Files.isRegularFile(executable))
