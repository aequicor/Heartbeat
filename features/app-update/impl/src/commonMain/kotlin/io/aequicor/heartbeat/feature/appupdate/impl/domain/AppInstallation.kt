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
 * the id of the bundle train the launcher started, null for a full-installer launch that runs no train yet.
 * [hasLauncher]: the launch has a launcher that applies trains (also without a train yet: it is then offered the
 * trains published after its own release); without one it is never offered a train.
 * [badTrains] are the trains the launcher rolled back after they failed to start: never offered again.
 * [pendingTrains] are already queued for the launcher's next start: nothing older than or equal to them is offered.
 * [channel] is the user's choice at the time of the check.
 */
internal data class AppInstallation(
    val version: AppVersion,
    val repository: GitHubRepository,
    val platform: ReleasePlatform,
    val train: String? = null,
    val hasLauncher: Boolean = train != null,
    val channel: UpdateChannel = UpdateChannel.Stable,
    val badTrains: Set<String> = emptySet(),
    val pendingTrains: Set<String> = emptySet(),
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
