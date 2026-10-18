package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.logging.Log
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Only freshly allocated helper files: cleanup never masks a Git result or its original failure. */
internal fun cleanupGitTemporary(path: Path) {
    repeat(DELETE_ATTEMPTS) { attempt ->
        try {
            Files.deleteIfExists(path)
            return
        } catch (error: FileSystemException) {
            if (attempt == DELETE_ATTEMPTS - 1) {
                temporaryLog.w(
                    IllegalStateException("GitTemporaryCleanupDeferred (${error::class.simpleName.orEmpty()})"),
                ) {
                    "Temporary Git file cleanup deferred until process exit"
                }
                path.toFile().deleteOnExit()
            } else {
                temporaryLog.v { "Retry temporary Git file cleanup after a sharing violation" }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(DELETE_POLL_MILLIS))
            }
        }
    }
}

private val temporaryLog = Log.tag("GitTemporaryFiles")
private const val DELETE_ATTEMPTS = 20
private const val DELETE_POLL_MILLIS = 50L
