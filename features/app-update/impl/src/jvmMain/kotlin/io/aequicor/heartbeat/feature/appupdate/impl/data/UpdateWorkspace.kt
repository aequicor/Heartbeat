package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
import io.aequicor.heartbeat.feature.appupdate.impl.domain.PreviousAttempt
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The update files under [root]: one directory per release with its download, prepared copy and helper script, the
 * helper's log and the marker of a started installation. Blocking IO: call on the IO dispatcher.
 */
@OptIn(ExperimentalPathApi::class)
internal class UpdateWorkspace(private val root: Path) {
    private val log = Log.tag("UpdateWorkspace")
    private val marker: Path get() = root.resolve(MARKER)

    /** The log the installer helper appends to; kept after a failed installation. */
    val helperLog: Path get() = root.resolve(HELPER_LOG)

    /** An empty directory for the files of [version]; directories of other releases are removed. */
    fun releaseDirectory(version: String): Path = storage {
        requireNotNull(AppVersion.parse(version)) { "Invalid release version" }
        Files.createDirectories(root)
        root.listDirectoryEntries().filter { it.isDirectory() }.forEach { it.deleteRecursively() }
        Files.createDirectories(root.resolve(version))
    }

    /** Records that the installer of [version] is about to start; the next launch reads it with [takeOutcome]. */
    fun markStarted(version: String): Unit = storage {
        val temporary = root.resolve("$MARKER.tmp")
        Files.writeString(temporary, version)
        Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    /** Forgets a marker written by [markStarted] when its installer could not be started. */
    fun clearStarted() {
        deleteQuietly(marker)
    }

    /**
     * How the installation started by an earlier launch ended for the [installed] version. Forgets it and removes
     * leftover release files; the helper log stays only after a failure.
     */
    fun takeOutcome(installed: AppVersion): PreviousAttempt {
        val target = try {
            if (Files.exists(marker)) Files.readString(marker).trim() else null
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "update marker could not be read" }
            ""
        }
        deleteQuietly(marker)
        if (Files.isDirectory(root)) {
            try {
                root.listDirectoryEntries().filter { it.isDirectory() }.forEach { it.deleteRecursively() }
            } catch (e: IOException) {
                log.w(e.withoutDetails()) { "previous update files could not be removed" }
            }
        }
        val outcome = when {
            target == null -> PreviousAttempt.None
            AppVersion.parse(target) == installed -> PreviousAttempt.Succeeded
            else -> PreviousAttempt.Failed
        }
        when (outcome) {
            PreviousAttempt.None -> Unit

            PreviousAttempt.Succeeded -> {
                log.i { "update installed version=$installed" }
                deleteQuietly(helperLog)
            }

            PreviousAttempt.Failed -> log.w {
                "update did not finish target=${target.orEmpty()} installed=$installed, see ${helperLog.name}"
            }
        }
        return outcome
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "update file could not be removed" }
        }
    }

    // The file system message names the user's paths: only the kind of failure travels on, so it is not the cause.
    @Suppress("SwallowedException")
    private fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw AppUpdateException(AppUpdateFailure.Storage, "update files could not be written", e.withoutDetails())
    }

    private companion object {
        const val MARKER = "pending-update"
        const val HELPER_LOG = "helper.log"
    }
}

/**
 * Folder of the update files on [host]: machine-local `%LOCALAPPDATA%` on Windows (never the roaming profile),
 * the app's data folder elsewhere. The Windows uninstaller removes both with the rest of the app's data.
 */
internal fun updatesRoot(host: HostPlatform, storage: StorageRoot): Path = when (host) {
    HostPlatform.Windows -> {
        val home = System.getProperty("user.home").orEmpty()
        Path.of(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "Aequicor", "Heartbeat", "updates")
    }

    HostPlatform.MacOs, HostPlatform.Linux, HostPlatform.Android, HostPlatform.Ios -> Path.of(storage.path(), "updates")
}
