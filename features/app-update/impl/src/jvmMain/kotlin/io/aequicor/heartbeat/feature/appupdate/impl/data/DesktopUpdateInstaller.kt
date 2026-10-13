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
import io.aequicor.heartbeat.feature.appupdate.impl.domain.withoutDetails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Installs a release on macOS or Windows. Everything that can fail runs while the app still works: the bundle must
 * be replaceable before anything is downloaded, the download is verified and, on macOS, unpacked and checked. Only
 * then is the installation marked and the helper started; it waits for this process to quit, replaces the app and
 * starts the new version. The next launch compares its version with the mark ([previousAttempt]).
 */
internal class DesktopUpdateInstaller(
    private val workspace: UpdateWorkspace,
    private val downloader: UpdateDownloader,
    private val commands: CommandRunner,
    private val launch: LaunchProperties,
    private val io: CoroutineDispatcher,
    private val processId: () -> Long = { ProcessHandle.current().pid() },
    private val startHelper: (command: List<String>, output: Path) -> Unit = commands::startDetached,
) : UpdateInstaller {
    private val log = Log.tag("AppUpdate")
    private val mac = MacBundleUpdate(commands)

    override suspend fun previousAttempt(installed: AppVersion): PreviousAttempt =
        withContext(io) { workspace.takeOutcome(installed) }

    override suspend fun install(installation: AppInstallation, release: AppRelease, progress: InstallProgressSink) {
        val launcher = launch.launcher ?: updateFailure(AppUpdateFailure.InstallFailed)
        val isMac = installation.platform.os == MAC_OS
        val bundle = if (isMac) {
            val bundle = mac.bundleOf(launcher)
            if (bundle == null) {
                log.w { "the launcher is outside an app bundle" }
                updateFailure(AppUpdateFailure.ManualInstallRequired)
            }
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
        try {
            startHelper(command, workspace.helperLog)
        } catch (e: AppUpdateException) {
            workspace.clearStarted()
            throw e
        }
        log.i { "update helper waits for the app to quit version=${release.version}" }
    }

    /** Copies the helper [name] from the app's resources into [directory]. */
    @Suppress("SwallowedException") // The file system message names the user's paths; only the kind travels on.
    private fun script(directory: Path, name: String): Path = try {
        val target = directory.resolve(name)
        val resource = checkNotNull(javaClass.getResourceAsStream("/app-update/$name")) { "Missing update helper" }
        resource.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        target
    } catch (e: IOException) {
        log.w(e.withoutDetails()) { "update helper could not be written" }
        throw AppUpdateException(AppUpdateFailure.Storage, e.withoutDetails())
    }

    private companion object {
        const val MAC_OS = "macos"
        const val MAC_SCRIPT = "apply-macos.sh"
        const val WINDOWS_SCRIPT = "apply-windows.ps1"
        const val INSTALL_LOG = "install.log"
    }
}
