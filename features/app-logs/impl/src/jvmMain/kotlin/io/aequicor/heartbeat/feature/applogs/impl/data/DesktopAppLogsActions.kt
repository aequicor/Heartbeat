package io.aequicor.heartbeat.feature.applogs.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.desktopdialogs.DesktopFileDialogs
import io.aequicor.heartbeat.core.logging.DesktopLogFiles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOperation
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOutcome
import io.aequicor.heartbeat.feature.applogs.impl.di.scope.AppLogsScope
import io.aequicor.heartbeat.feature.applogs.impl.domain.AppLogsActions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Snapshot-based native operations. Dialog time never changes the selected log window. Exports are staged
 * outside the managed log directory and committed by rename; cancellation removes temporary files.
 */
@ContributesBinding(AppLogsScope::class)
@Inject
internal class DesktopAppLogsActions(
    private val files: DesktopLogFiles,
    private val dialogs: DesktopFileDialogs,
    private val dispatchers: DispatcherProvider,
) : AppLogsActions {
    private val log = Log.tag("DesktopAppLogs")

    override suspend fun execute(operation: AppLogsOperation): AppLogsOutcome = when (operation) {
        AppLogsOperation.CopyRecent -> copyRecent()
        AppLogsOperation.SaveRecent -> save(isArchive = false)
        AppLogsOperation.SaveArchive -> save(isArchive = true)
        AppLogsOperation.OpenFolder -> openFolder()
    }

    private suspend fun copyRecent(): AppLogsOutcome {
        val text = withContext(dispatchers.io) { files.recentText() }
        if (text.isEmpty()) return AppLogsOutcome.Empty
        withContext(dispatchers.main) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
        log.i { "Copied recent logs" }
        return AppLogsOutcome.Success
    }

    private suspend fun save(isArchive: Boolean): AppLogsOutcome = withContext(dispatchers.io) {
        val extension = if (isArchive) "zip" else "log"
        val snapshot = Files.createTempFile("heartbeat-logs-", ".$extension")
        try {
            if (isArchive) files.writeArchive(snapshot) else files.writeRecent(snapshot)
            if (isSnapshotEmpty(snapshot, isArchive)) return@withContext AppLogsOutcome.Empty
            val name = if (isArchive) "heartbeat-logs.zip" else "heartbeat-last-10-minutes.log"
            val target = dialogs.pickSaveLocation(suggestedName = name, extensions = listOf(extension))
                ?: return@withContext AppLogsOutcome.Cancelled
            commitSnapshot(snapshot, Path.of(target))
            log.i { "Saved log snapshot archive=$isArchive" }
            AppLogsOutcome.Success
        } finally {
            deleteTemporary(snapshot)
        }
    }

    private fun isSnapshotEmpty(snapshot: Path, isArchive: Boolean): Boolean = if (isArchive) {
        ZipFile(snapshot.toFile()).use { it.size() == 0 }
    } else {
        Files.size(snapshot) == 0L
    }

    /** Resolves aliases before checking the managed directory, protecting live sinks from overwrite. */
    private suspend fun commitSnapshot(snapshot: Path, requested: Path) {
        val target = requested.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent).toRealPath()
        files.validateExportDestination(target)
        val temporary = Files.createTempFile(parent, ".heartbeat-export-", ".tmp")
        try {
            Files.copy(snapshot, temporary, StandardCopyOption.REPLACE_EXISTING)
            currentCoroutineContext().ensureActive()
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                log.w(error.redactedFailure()) { "Atomic export rename unavailable; using same-directory rename" }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            deleteTemporary(temporary)
        }
    }

    private suspend fun openFolder(): AppLogsOutcome = withContext(dispatchers.io) {
        Files.createDirectories(files.directory)
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(files.directory.toFile())
        } else {
            check(System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true)) {
                "System file manager is unavailable"
            }
            val process = ProcessBuilder("xdg-open", files.directory.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            try {
                check(process.waitFor(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "File manager request timed out" }
                check(process.exitValue() == 0) { "File manager rejected the request" }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        log.i { "Opened log directory" }
        AppLogsOutcome.Success
    }

    private fun deleteTemporary(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (error: IOException) {
            log.w(error.redactedFailure()) { "Unable to remove temporary log snapshot" }
        }
    }
}

private const val OPEN_TIMEOUT_SECONDS = 10L
