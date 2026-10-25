package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppInstallation
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
import io.aequicor.heartbeat.feature.appupdate.impl.domain.InstallProgressSink
import io.aequicor.heartbeat.feature.appupdate.impl.domain.PreviousAttempt
import io.aequicor.heartbeat.feature.appupdate.impl.domain.UpdateInstaller
import io.aequicor.heartbeat.feature.appupdate.impl.domain.updateFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Installs a release on macOS or Windows. Download and validation run while the app still works: the bundle must
 * be replaceable before anything is downloaded, the download is verified and, on macOS, unpacked and checked. Only
 * then is the installation marked and the helper started. After this process quits, the macOS helper first stages
 * the complete bundle on the installation volume while preserving the installed app, then swaps it and relaunches.
 * The next launch compares its version with the mark ([previousAttempt]).
 * Linux DEB installations belong to the system package manager: update offers lead to the release page for
 * manual installation, without downloading or trying to replace root-owned files from the desktop process.
 * A bundle train ([AppRelease.train]) is prepared for the platform launcher by [TrainUpdate] instead and activates at
 * the launcher's next start. Nothing is replaced, so the helper only waits for this process to quit and starts the
 * same launcher again (macOS: an inline `sh` that `open`s the bundle; Windows: `apply-windows.ps1` without an
 * installer). No mark is written: the app version does not change, and the launcher alone rolls a bad train back.
 * Once the train is queued the update has succeeded whatever happens to the relaunch: without a known launcher, or
 * when the helper cannot start, the app still quits and the user starts it again, which applies the train.
 */
internal class DesktopUpdateInstaller(
    private val workspace: UpdateWorkspace,
    private val downloader: UpdateDownloader,
    private val mac: MacBundleUpdate,
    private val trains: TrainUpdate,
    private val io: CoroutineDispatcher,
    private val host: InstallerLaunch,
) : UpdateInstaller {
    private val launch: LaunchProperties get() = host.properties
    private val log = Log.tag("DesktopUpdateInstaller")
    private val outcome = Mutex()
    private var previous: PreviousAttempt? = null

    // Read once per process: the mark and the files of an earlier attempt are cleared before anything is downloaded.
    override suspend fun previousAttempt(installed: AppVersion): PreviousAttempt = outcome.withLock {
        previous ?: withContext(io) { workspace.takeOutcome(installed) }.also { previous = it }
    }

    override suspend fun install(installation: AppInstallation, release: AppRelease, progress: InstallProgressSink) {
        previousAttempt(installation.version)
        if (installation.platform.isManualInstallRequired) {
            updateFailure(AppUpdateFailure.ManualInstallRequired, "Linux updates use the system package manager")
        }
        if (release.train != null) {
            installTrain(installation, release, progress)
            return
        }
        val launcher = launch.launcher ?: updateFailure(AppUpdateFailure.InstallFailed, "the launcher is unknown")
        val bundle = if (installation.platform.os == MAC_OS) {
            val bundle = mac.bundleOf(launcher)
                ?: updateFailure(AppUpdateFailure.ManualInstallRequired, "the launcher is outside an app bundle")
            withContext(io) { mac.requireReplaceable(bundle) }
            bundle
        } else {
            null
        }
        val directory = withContext(io) { workspace.releaseDirectory(release.version) }
        val download = directory.resolve(release.assetName)
        downloader.download(release, download) { received, total -> progress.downloading(received, total) }
        progress.preparing()
        withContext(io) {
            val command = if (bundle != null) {
                val staged = mac.prepare(download, release.version, directory)
                mac.helperCommand(script(directory, MAC_SCRIPT), host.processId(), bundle, staged)
            } else {
                WindowsSetupUpdate.helperCommand(
                    script(directory, WINDOWS_SCRIPT),
                    host.processId(),
                    download,
                    launcher,
                    directory.resolve(INSTALL_LOG),
                )
            }
            start(release, command)
        }
    }

    /**
     * Queues the train for the launcher, then starts the helper that relaunches it once this process quits. Only the
     * queue can fail the update: a launch that does not know its launcher queues the train without a helper, and a
     * helper that cannot be prepared or started is logged, because the queued train applies at the next start anyway.
     * Failing then would report an update that is in fact queued, and the next check would offer nothing.
     */
    private suspend fun installTrain(
        installation: AppInstallation,
        release: AppRelease,
        progress: InstallProgressSink,
    ) {
        val root = launch.launcherRoot ?: updateFailure(AppUpdateFailure.InstallFailed, "no launcher root")
        trains.prepare(installation.repository, root, release, progress)
        val launcher = launch.launcher
        if (launcher == null) {
            log.w { "train queued without a relaunch: the launcher is unknown train=${release.version}" }
            return
        }
        try {
            withContext(io) {
                // The helper's log lives with it in the relaunch directory, which the next launch removes.
                val directory = workspace.helperDirectory()
                val command = if (installation.platform.os == MAC_OS) {
                    relaunchMacCommand(host.processId(), mac.bundleOf(launcher) ?: launcher)
                } else {
                    WindowsSetupUpdate.relaunchCommand(script(directory, WINDOWS_SCRIPT), host.processId(), launcher)
                }
                host.startHelper(command, directory.resolve(RELAUNCH_LOG))
            }
            log.i { "relaunch helper waits for the app to quit train=${release.version}" }
        } catch (e: AppUpdateException) {
            log.w(e) { "train queued, the app is not relaunched train=${release.version} failure=${e.failure}" }
        }
    }

    private fun start(release: AppRelease, command: List<String>) {
        workspace.markStarted(release.version)
        var isStarted = false
        try {
            host.startHelper(command, workspace.helperLog)
            isStarted = true
        } finally {
            // Without a running helper nothing is installed: the next launch must not report a failed attempt.
            if (!isStarted) workspace.clearStarted()
        }
        log.i { "update helper waits for the app to quit version=${release.version}" }
    }

    /**
     * Copies the helper [name] from the app's resources into [directory]. A missing resource is
     * [AppUpdateFailure.InstallFailed] (a broken build), so a train that is already queued only skips the relaunch.
     */
    // The file system message names the user's paths: only the kind of failure travels on, so it is not the cause.
    @Suppress("SwallowedException")
    private fun script(directory: Path, name: String): Path = try {
        val target = directory.resolve(name)
        val resource = javaClass.getResourceAsStream("/app-update/$name")
            ?: updateFailure(AppUpdateFailure.InstallFailed, "update helper $name is missing")
        resource.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        target
    } catch (e: IOException) {
        throw AppUpdateException(AppUpdateFailure.Storage, "update helper could not be written", e.withoutDetails())
    }

    private companion object {
        /**
         * Waits up to ten minutes for process `$1` to quit, then starts `$2` again: [OPEN] (`$3`, a fixed path rather
         * than whatever `open` comes first on PATH) for an app bundle, the executable itself otherwise. Paths travel as
         * arguments, never inside the script.
         */
        const val MAC_RELAUNCH = "pid=${'$'}1; app=${'$'}2; n=0; " +
            "while kill -0 \"${'$'}pid\" 2>/dev/null; do " +
            "if [ \"${'$'}n\" -ge 600 ]; then echo 'the app did not quit; not relaunched'; exit 1; fi; " +
            "sleep 1; n=${'$'}((n + 1)); done; " +
            "if [ -d \"${'$'}app\" ]; then exec \"${'$'}3\" \"${'$'}app\"; else exec \"${'$'}app\"; fi"

        fun relaunchMacCommand(pid: Long, target: Path): List<String> =
            listOf("/bin/sh", "-c", MAC_RELAUNCH, "heartbeat-relaunch", "$pid", "$target", OPEN)

        const val OPEN = "/usr/bin/open"
        const val RELAUNCH_LOG = "relaunch.log"

        const val MAC_OS = "macos"
        const val MAC_SCRIPT = "apply-macos.sh"
        const val WINDOWS_SCRIPT = "apply-windows.ps1"
        const val INSTALL_LOG = "install.log"
    }
}

/**
 * This launch of the app as the installer sees it: what the launcher states ([properties]), its process id and how it
 * starts a helper [command][startHelper] that outlives it, appending its output to a log file.
 */
internal data class InstallerLaunch(
    val properties: LaunchProperties,
    val startHelper: (command: List<String>, output: Path) -> Unit,
    val processId: () -> Long = { ProcessHandle.current().pid() },
)
