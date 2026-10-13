package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
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
    private val log = Log.tag("AppUpdate")

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

    private companion object {
        const val APP_VERSION = "heartbeat.app.version"
        const val UPDATE_REPOSITORY = "heartbeat.app.update.repository"
        const val APP_PATH = "jpackage.app-path"
        const val OS_ARCH = "os.arch"
    }
}

/** An installed release build on a desktop OS with a published build updates itself; any other launch does not. */
internal class DesktopInstallationProbe(private val host: HostPlatform, private val launch: LaunchProperties) :
    InstallationProbe {
    override fun current(): InstallationCheck {
        val version = launch.version
        val repository = launch.repository
        if (version == null || repository == null || launch.launcher == null) {
            return InstallationCheck.Unsupported(UnsupportedReason.NotReleaseBuild)
        }
        val platform = releasePlatform(host, launch.architecture)
            ?: return InstallationCheck.Unsupported(UnsupportedReason.UnsupportedPlatform)
        return InstallationCheck.Supported(AppInstallation(version, repository, platform))
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
        else -> null
    }
}

private fun InvalidPathException.withoutPath(): Throwable = IllegalArgumentException("InvalidPathException")
