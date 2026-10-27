package io.aequicor.heartbeat.feature.appupdate.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.api.UnsupportedReason
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppInstallation
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.InstallationCheck
import io.aequicor.heartbeat.feature.appupdate.impl.domain.InstallationProbe
import io.aequicor.heartbeat.feature.appupdate.impl.domain.ReleasePlatform
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * What the jpackage launcher states about this launch. Only the release launcher names the update repository
 * (`platform-main/desktop/build.gradle.kts`); `jpackage.app-path` is the installed launcher executable.
 */
internal class LaunchProperties(private val property: (String) -> String? = System::getProperty) {
    private val log = Log.tag("LaunchProperties")

    /** The installed version, or null for a launch without one (`development`). */
    val version: AppVersion? get() = property(APP_VERSION)?.let(AppVersion::parse)

    /** Where updates are published; only release builds have it. */
    val repository: GitHubRepository? get() = property(UPDATE_REPOSITORY)?.let(GitHubRepository::parse)

    /** The launcher executable of the installed app, or null outside an installed app. */
    val launcher: Path?
        get() = property(APP_PATH)?.takeIf { it.isNotBlank() }?.let { path ->
            try {
                Path.of(path).toAbsolutePath()
            } catch (e: InvalidPathException) {
                log.w(e.withoutPath()) { "launcher path is invalid" }
                null
            }
        }

    /** The JVM's architecture name (`aarch64`, `amd64`, `x86_64`). */
    val architecture: String get() = property(OS_ARCH).orEmpty()

    /** The id of the bundle train the platform launcher started; null for a launch without a launcher. */
    val trainId: String? get() = property(TRAIN)?.takeIf { it.isNotBlank() }

    /**
     * The trains the platform launcher rolled back because they did not start (`heartbeat.train.bad`, comma-separated);
     * empty for a launch without a launcher.
     */
    val badTrains: Set<String>
        get() = property(BAD_TRAINS).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * The launcher's root directory (`store/`, `staging/`, `pending/` below it), stated by the platform launcher;
     * null for a launch without one.
     */
    val launcherRoot: Path?
        get() = property(LAUNCHER_ROOT)?.takeIf { it.isNotBlank() }?.let { path ->
            try {
                Path.of(path).toAbsolutePath()
            } catch (e: InvalidPathException) {
                log.w(e.withoutPath()) { "launcher root is invalid" }
                null
            }
        }

    private companion object {
        const val TRAIN = "heartbeat.train"
        const val BAD_TRAINS = "heartbeat.train.bad"
        const val LAUNCHER_ROOT = "heartbeat.launcher.root"
        const val APP_VERSION = "heartbeat.app.version"
        const val UPDATE_REPOSITORY = "heartbeat.app.update.repository"
        const val APP_PATH = "jpackage.app-path"
        const val OS_ARCH = "os.arch"
    }
}

/**
 * An installed release build on a desktop OS with a published build updates itself; any other launch does not. The
 * trains queued for the launcher are read on every check: a train queued by this launch counts at once.
 */
@Inject
internal class DesktopInstallationProbe(private val platform: PlatformInfo, private val launch: LaunchProperties) :
    InstallationProbe {
    override fun current(): InstallationCheck {
        val version = launch.version
        val repository = launch.repository
        if (version == null || repository == null || launch.launcher == null) {
            return InstallationCheck.Unsupported(UnsupportedReason.NotReleaseBuild)
        }
        val build = releasePlatform(platform.host, launch.architecture)
            ?: return InstallationCheck.Unsupported(UnsupportedReason.UnsupportedPlatform)
        // Trains need the launcher that applies them: without its root there is no train to compare or install.
        val root = launch.launcherRoot
        val train = launch.trainId.takeIf { root != null }
        return InstallationCheck.Supported(
            AppInstallation(
                version = version,
                repository = repository,
                platform = build,
                train = train,
                hasLauncher = root != null,
                badTrains = if (root != null) launch.badTrains else emptySet(),
                pendingTrains = if (root != null) pendingTrains(root) else emptySet(),
            ),
        )
    }
}

/** The release build for [host] on the JVM architecture [architecture], or null when none is published. */
internal fun releasePlatform(host: HostPlatform, architecture: String): ReleasePlatform? {
    val isArm = architecture == "aarch64" || architecture == "arm64"
    val isX64 = architecture == "amd64" || architecture == "x86_64"
    return when {
        host == HostPlatform.MacOs && isArm -> ReleasePlatform.MacOsArm64
        host == HostPlatform.MacOs && isX64 -> ReleasePlatform.MacOsX64
        host == HostPlatform.Windows && isArm -> ReleasePlatform.WindowsArm64
        host == HostPlatform.Windows && isX64 -> ReleasePlatform.WindowsX64
        host == HostPlatform.Linux && isX64 -> ReleasePlatform.LinuxX64
        else -> null
    }
}

private fun InvalidPathException.withoutPath(): Throwable = IllegalArgumentException("InvalidPathException")
