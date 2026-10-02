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
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
        val isRunnable = executable != null && Files.isRegularFile(executable) && Files.isExecutable(executable)
        val version = when {
            executable == null || !isRunnable -> null
            startup.source == InstallSource.Bundled -> processes.bundledVersion() ?: version(executable, startup)
            else -> version(executable, startup)
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

    /** `pi --version`, offline, bounded and cancellable; an executable that does not answer has no known version. */
    private suspend fun version(executable: Path, startup: PiStartup): String? = try {
        val builder = ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true)
        applyPiEnvironment(builder.environment(), startup)
        builder.environment()["PI_OFFLINE"] = "1"
        builder.environment()["PI_SKIP_VERSION_CHECK"] = "1"
        val process = builder.start()
        try {
            process.outputStream.close()
            val isDone = runInterruptible { process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            if (isDone) parsePiVersion(process.inputStream.readNBytes(MAX_VERSION_BYTES).decodeToString()) else null
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    } catch (e: IOException) {
        log.w(e.withoutDetails()) { "Pi version could not be read" }
        null
    }

    private companion object {
        const val VERSION_TIMEOUT_SECONDS = 10L
        const val MAX_VERSION_BYTES = 4096
    }
}
