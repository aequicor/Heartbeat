package io.aequicor.heartbeat.feature.appupdate.impl.domain

import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.api.UnsupportedReason

/** An installed release build: its [version], where its updates are published and which build it installs. */
internal data class AppInstallation(
    val version: AppVersion,
    val repository: GitHubRepository,
    val platform: ReleasePlatform,
)

/** Whether this launch can update itself. */
internal sealed interface InstallationCheck {
    /** An installed release build. */
    data class Supported(val installation: AppInstallation) : InstallationCheck

    /** A launch that never replaces itself. */
    data class Unsupported(val reason: UnsupportedReason) : InstallationCheck
}

/** Determines what this launch is; fixed for the lifetime of the process. */
internal fun interface InstallationProbe {
    /** The installation of this launch, or why it cannot update itself. */
    fun current(): InstallationCheck
}
