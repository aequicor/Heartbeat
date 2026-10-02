package io.aequicor.heartbeat.core.desktopdialogs

import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * A fresh thread lets Windows initialize STA without inheriting a pool thread's COM apartment.
 * The thread owns no resources after [dialog] returns. Deliberately waits for modal dismissal even
 * when the caller is cancelled: COM interfaces and the disabled owner must be restored before return.
 * Every failure (including native library linkage errors) resumes the caller for centralized logging.
 */
internal suspend fun <T> onDialogThread(dialog: () -> T): T = suspendCoroutine { continuation ->
    thread(start = false, name = "Heartbeat-file-dialog", isDaemon = true) {
        continuation.resume(dialog())
    }.apply {
        // Forward uncaught native failures and cancellation to the suspended caller after stack unwinding.
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
            continuation.resumeWithException(error)
        }
    }.start()
}
