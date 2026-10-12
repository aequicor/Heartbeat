package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.desktop.QuitResponse
import java.util.concurrent.atomic.AtomicReference

/** Coalesces repeated close gestures while native callbacks still need the window's event loop. */
@Composable
internal fun rememberDesktopCloseHandler(
    participants: Set<AppShutdownParticipant>,
    destroy: () -> Unit,
    exit: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    var isClosing by remember { mutableStateOf(false) }
    val log = remember { Log.tag("DesktopShutdown") }
    return {
        if (!isClosing) {
            isClosing = true
            scope.launch {
                log.i { "Close window after native resources finish" }
                shutdownDesktop(participants, destroy, exit)
            }
        }
    }
}

/**
 * Routes the macOS application quit (Cmd+Q, Dock "Quit", logout, restart) through [closeRequest]. The AWT default
 * calls `System.exit` directly and would skip every [AppShutdownParticipant]. The system request is deferred, never
 * cancelled — cancelling it would also abort a logout or restart — and [DesktopSystemQuit.complete] answers it once
 * the close flow exits. Hosts without a quit handler keep their default behavior: on Windows the window close request
 * is the only exit path.
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

/** Holds a pending system quit until the window close flow has exited the application. */
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
 * Keeps the event loop alive for native callbacks while participants flush, in parallel and within
 * [timeoutMillis] overall. A failing or slow participant never blocks the exit: the failure is logged and returned,
 * then the lifecycle is destroyed and the process exits anyway — also when destroying the lifecycle fails — so the
 * window can always be closed.
 */
internal suspend fun shutdownDesktop(
    participants: Set<AppShutdownParticipant>,
    destroy: () -> Unit,
    exit: () -> Unit,
    timeoutMillis: Long = DESKTOP_SHUTDOWN_TIMEOUT_MILLIS,
): Throwable? {
    val log = Log.tag("DesktopShutdown")
    val failures = withTimeoutOrNull(timeoutMillis) { awaitParticipants(participants) }
        ?: listOf(IllegalStateException("Native shutdown did not finish within $timeoutMillis ms"))
    val error = failures.firstOrNull()?.also { first -> failures.drop(1).forEach(first::addSuppressed) }
    if (error != null) log.e(error) { "Native shutdown failed; exiting anyway" }
    var destroyFailure: Exception? = null
    try {
        destroy()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "Application lifecycle failed to stop; exiting anyway" }
        destroyFailure = e
    } finally {
        exit()
    }
    return error ?: destroyFailure
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
