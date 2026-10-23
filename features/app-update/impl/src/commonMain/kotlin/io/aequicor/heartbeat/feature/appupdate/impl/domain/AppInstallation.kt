package io.aequicor.heartbeat.feature.appupdate.impl.domain

import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.api.UnsupportedReason

/** Which releases an update check considers. */
internal enum class UpdateChannel {
    /** Full releases only: drafts and pre-releases are ignored. */
    Stable,

    /** Opt-in: also the newest bundle train, a pre-release with `train-manifest.json`. */
    Beta,
}

/**
 * An installed release build: its [version], where its updates are published and which build it installs. [train] is
 * the id of the bundle train the launcher started, null for a launch without a launcher (it is then never offered a
 * train). [channel] is the user's choice at the time of the check.
 */
internal data class AppInstallation(
    val version: AppVersion,
    val repository: GitHubRepository,
    val platform: ReleasePlatform,
    val train: String? = null,
    val channel: UpdateChannel = UpdateChannel.Stable,
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
