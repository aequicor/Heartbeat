package io.aequicor.heartbeat.feature.appupdate.impl.domain

import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion

/** How the installation an earlier launch started has ended. */
internal enum class PreviousAttempt {
    /** No installation was started. */
    None,

    /** This launch runs the version that was installed. */
    Succeeded,

    /** This launch still runs another version: the installer did not finish. */
    Failed,
}

/** Progress of [UpdateInstaller.install], reported to the machine. */
internal interface InstallProgressSink {
    /** [received] bytes of the release file of [total] bytes, when the size is known. */
    suspend fun downloading(received: Long, total: Long?)

    /** The download is verified; it is being unpacked and the installer is being started. */
    suspend fun preparing()
}

/** Replaces the installed app with a release; desktop only. */
internal interface UpdateInstaller {
    /**
     * How the installation started by an earlier launch ended for the [installed] version. Determined once per launch,
     * before any download; the mark and the files of that attempt are then cleared.
     */
    suspend fun previousAttempt(installed: AppVersion): PreviousAttempt

    /**
     * Downloads and verifies [release], prepares it and starts the installer that waits for this process to end,
     * replaces the app and starts the new version. A bundle train is queued for the platform launcher instead, and the
     * helper only starts the launcher again, which applies it. Returns once the helper is waiting; the app must then
     * quit.
     * Fails with [AppUpdateException] and leaves the installed app unchanged.
     */
    suspend fun install(installation: AppInstallation, release: AppRelease, progress: InstallProgressSink)
}
