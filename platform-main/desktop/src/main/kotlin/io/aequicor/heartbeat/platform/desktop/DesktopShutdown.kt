package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.desktop.QuitResponse
import java.util.concurrent.atomic.AtomicReference

/** Explicit quit stops owned children; an update restart preserves the installer waiting for this JVM. */
internal data class DesktopQuitRequests(val quit: () -> Unit, val restart: () -> Unit)

/** Coalesces all quit gestures while native callbacks still need the application's event loop. */
@Composable
internal fun rememberDesktopCloseHandler(
    participants: Set<AppShutdownParticipant>,
    dispatchers: DispatcherProvider,
    stopScopes: suspend () -> Unit,
    destroy: () -> Unit,
    exit: () -> Unit,
): DesktopQuitRequests {
    val scope = rememberCoroutineScope()
    val currentShutdown by rememberUpdatedState<suspend (DesktopProcessCleanup) -> Unit> { processes ->
        shutdownDesktop(participants, destroy, exit, stopScopes = stopScopes, processes = processes)
    }
    return remember(scope, dispatchers) {
        DesktopQuitController(scope, DesktopChildProcesses(dispatchers)) { currentShutdown(it) }
    }.requests
}

/**
 * Routes the macOS application quit (Cmd+Q, Dock "Quit", logout, restart) through [closeRequest]. The AWT default
 * calls `System.exit` directly and would skip every [AppShutdownParticipant]. The system request is deferred, never
 * cancelled — cancelling it would also abort a logout or restart — and [DesktopSystemQuit.complete] answers it once
 * the quit flow exits. On other hosts explicit exit is available from the tray menu.
 */
internal fun installDesktopQuitHandler(quit: DesktopSystemQuit, closeRequest: () -> Unit) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) return
    val log = Log.tag("DesktopShutdown")
    desktop.setQuitHandler { _, response ->
        log.i { "Application quit requested; close after native resources finish" }
        quit.defer(response)
        EventQueue.invokeLater(closeRequest)
    }
}

/** Holds a pending system quit until the quit flow has exited the application. */
internal class DesktopSystemQuit {
    private val pending = AtomicReference<QuitResponse?>(null)

    /** Keeps the latest system quit request; the close flow already in progress answers it. */
    fun defer(response: QuitResponse) {
        pending.set(response)
    }

    /** Lets the system finish its quit, and a logout or restart waiting for it; a no-op without a request. */
    fun complete() {
        pending.getAndSet(null)?.performQuit()
    }
}

/**
 * Captures child identities before native owners can orphan them, flushes native resources while the event loop
 * is alive, closes and awaits application scopes, destroys UI, then stops retained children before exiting.
 * Failures are collected without skipping later cleanup. [DesktopQuitController] decides whether to stop children
 * at the final stage, so an update restart requested during cleanup preserves its waiting installer.
 */
internal suspend fun shutdownDesktop(
    participants: Set<AppShutdownParticipant>,
    destroy: () -> Unit,
    exit: () -> Unit,
    timeoutMillis: Long = DESKTOP_SHUTDOWN_TIMEOUT_MILLIS,
    stopScopes: suspend () -> Unit = {},
    processes: DesktopProcessCleanup? = null,
    stopAdmission: suspend () -> Unit = {},
): Throwable? {
    val failures = mutableListOf<Throwable>()
    try {
        shutdownStage(failures) { processes?.capture() }
        shutdownStage(failures) { stopAdmission() }
        shutdownStage(failures) {
            failures += boundedShutdown(timeoutMillis) { awaitParticipants(participants) }
                ?: listOf(IllegalStateException("Native shutdown did not finish within $timeoutMillis ms"))
        }
        shutdownStage(failures) {
            val areScopesClosed = boundedShutdown(timeoutMillis) {
                stopScopes()
                true
            }
            check(areScopesClosed == true) { "Application scopes did not stop within $timeoutMillis ms" }
        }
    } finally {
        withContext(NonCancellable) {
            try {
                shutdownStage(failures) { destroy() }
            } finally {
                try {
                    shutdownStage(failures) { processes?.stop() }
                } finally {
                    failures.firstOrNull()?.let { first ->
                        failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
                        Log.tag("DesktopShutdown").e(first) {
                            "Application cleanup failed; exiting after attempting all cleanup"
                        }
                    }
                    exit()
                }
            }
        }
    }
    return failures.firstOrNull()
}

private suspend fun shutdownStage(failures: MutableList<Throwable>, action: suspend () -> Unit) {
    try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.tag("DesktopShutdown").e(error) { "Application cleanup stage failed" }
        failures += error
    }
}

/**
 * Shutdown adapters may enter NonCancellable native cleanup. An independent job lets the host enforce its
 * deadline without waiting for such a child after timeout; final process cleanup and JVM exit still follow.
 * The job inherits the UI dispatcher, and is cancelled on every completion path.
 */
private suspend fun <T> boundedShutdown(timeoutMillis: Long, action: suspend () -> T): T? {
    val job = SupervisorJob()
    val scope = CoroutineScope(currentCoroutineContext() + job)
    val result = scope.async { action() }
    return try {
        withTimeoutOrNull(timeoutMillis) { result.await() }
    } finally {
        job.cancel()
    }
}

/** Runs every participant to completion; one failure never cancels the others. */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun awaitParticipants(participants: Set<AppShutdownParticipant>): List<Throwable> = supervisorScope {
    val runs = participants.map { participant -> async { participant.shutdown() } }
    runs.forEach { it.join() }
    runs.mapNotNull { it.getCompletionExceptionOrNull() }
}

/** Outer bound only: participants bound their own native waits (the browser runtime uses 60 s). */
private const val DESKTOP_SHUTDOWN_TIMEOUT_MILLIS = 75_000L
