package io.aequicor.heartbeat.feature.appupdate.api

/**
 * What the update button shows: the offered [version], how far its installation has come, and why the last attempt
 * ended. [pageUrl] is the release page for [AppUpdateFailure.ManualInstallRequired].
 */
public data class AppUpdateOffer(
    val version: String,
    val pageUrl: String,
    val phase: AppUpdatePhase,
    val failure: AppUpdateFailure? = null,
) {
    /** The button installs the release; a manual install opens [pageUrl] instead. */
    val isInstallable: Boolean
        get() = phase == AppUpdatePhase.Ready && failure != AppUpdateFailure.ManualInstallRequired
}

/** Stage of an offered update as the button shows it. */
public sealed interface AppUpdatePhase {
    /** Ready to install (again, after a [AppUpdateOffer.failure]). */
    public data object Ready : AppUpdatePhase

    /** Downloading; [percent] is null while the size is unknown. */
    public data class Downloading(val percent: Int?) : AppUpdatePhase

    /** Verifying and unpacking the download, starting the installer. */
    public data object Preparing : AppUpdatePhase

    /** The app is quitting so that the installer can replace it. */
    public data object Restarting : AppUpdatePhase
}

/** The offer to show in [this] state, or null when there is no newer release to show. */
public fun AppUpdateState.appUpdateOffer(): AppUpdateOffer? = when (this) {
    is AppUpdateState.Available -> release.offer(AppUpdatePhase.Ready, failure)

    is AppUpdateState.Checking -> offered?.offer(AppUpdatePhase.Ready)

    is AppUpdateState.Installing -> release.offer(
        when (progress) {
            is InstallProgress.Downloading -> AppUpdatePhase.Downloading(progress.percent())
            InstallProgress.Preparing -> AppUpdatePhase.Preparing
        },
    )

    is AppUpdateState.Restarting -> release.offer(AppUpdatePhase.Restarting)

    AppUpdateState.Inactive,
    AppUpdateState.UpToDate,
    is AppUpdateState.CheckFailed,
    is AppUpdateState.Unsupported,
    -> null
}

private fun AppRelease.offer(phase: AppUpdatePhase, failure: AppUpdateFailure? = null): AppUpdateOffer =
    AppUpdateOffer(version, pageUrl, phase, failure)

private fun InstallProgress.Downloading.percent(): Int? {
    val size = total?.takeIf { it > 0 } ?: return null
    return (received.coerceIn(0, size) * PERCENT / size).toInt()
}

private const val PERCENT = 100L
