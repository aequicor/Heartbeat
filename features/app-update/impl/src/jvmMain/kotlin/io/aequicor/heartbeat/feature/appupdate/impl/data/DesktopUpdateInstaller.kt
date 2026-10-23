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
 * the next start; there is no helper, so no mark is written and the launcher alone rolls a bad train back.
 */
internal class DesktopUpdateInstaller(
    private val workspace: UpdateWorkspace,
    private val downloader: UpdateDownloader,
    private val mac: MacBundleUpdate,
    private val trains: TrainUpdate,
    private val launch: LaunchProperties,
    private val io: CoroutineDispatcher,
    private val processId: () -> Long = { ProcessHandle.current().pid() },
    private val startHelper: (command: List<String>, output: Path) -> Unit,
) : UpdateInstaller {
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
            // The platform launcher applies a train at its next start: nothing is replaced or started here.
            val root = launch.launcherRoot ?: updateFailure(AppUpdateFailure.InstallFailed, "no launcher root")
            trains.prepare(installation.repository, root, release, progress)
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
                mac.helperCommand(script(directory, MAC_SCRIPT), processId(), bundle, staged)
            } else {
                WindowsSetupUpdate.helperCommand(
                    script(directory, WINDOWS_SCRIPT),
                    processId(),
                    download,
                    launcher,
                    directory.resolve(INSTALL_LOG),
                )
            }
            start(release, command)
        }
    }

    private fun start(release: AppRelease, command: List<String>) {
        workspace.markStarted(release.version)
        var isStarted = false
        try {
            startHelper(command, workspace.helperLog)
            isStarted = true
        } finally {
            // Without a running helper nothing is installed: the next launch must not report a failed attempt.
            if (!isStarted) workspace.clearStarted()
        }
        log.i { "update helper waits for the app to quit version=${release.version}" }
    }

    /** Copies the helper [name] from the app's resources into [directory]. */
    // The file system message names the user's paths: only the kind of failure travels on, so it is not the cause.
    @Suppress("SwallowedException")
    private fun script(directory: Path, name: String): Path = try {
        val target = directory.resolve(name)
        val resource = checkNotNull(javaClass.getResourceAsStream("/app-update/$name")) { "Missing update helper" }
        resource.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        target
    } catch (e: IOException) {
        throw AppUpdateException(AppUpdateFailure.Storage, "update helper could not be written", e.withoutDetails())
    }

    private companion object {
        const val MAC_OS = "macos"
        const val MAC_SCRIPT = "apply-macos.sh"
        const val WINDOWS_SCRIPT = "apply-windows.ps1"
        const val INSTALL_LOG = "install.log"
    }
}
