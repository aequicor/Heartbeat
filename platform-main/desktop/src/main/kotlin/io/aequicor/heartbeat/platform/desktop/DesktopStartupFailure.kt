package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.platform.dibundle.AppShutdownAccessors
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.closeScopes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Cleans a partially initialized graph before propagating the original launch failure to the lease owner. */
internal fun withFailedDesktopLaunchCleanup(cleanup: () -> Unit, launch: () -> Unit) {
    var hasCompletedLaunch = false
    try {
        launch()
        hasCompletedLaunch = true
    } finally {
        if (!hasCompletedLaunch) attemptFailedDesktopCleanup(cleanup)
    }
}

private fun attemptFailedDesktopCleanup(cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.tag("Desktop").e(error) { "Failed desktop launch cleanup could not be confirmed" }
    }
}

/** Called from the launcher thread after graph creation fails later in root/window initialization. */
internal fun shutdownFailedDesktopLaunch(graph: HeartbeatGraph, destroy: () -> Unit) {
    check(!SwingUtilities.isEventDispatchThread()) { "Failed launch cleanup must keep the UI dispatcher alive" }
    val shutdown = graph as AppShutdownAccessors
    val scope = CoroutineScope(SupervisorJob() + graph.dispatchers.main)
    val completed = CompletableFuture<Unit>()
    val cleanup = scope.async {
        shutdownDesktop(
            shutdown.shutdownParticipants,
            destroy,
            exit = {},
            stopScopes = shutdown::closeScopes,
            stopAdmission = { graph.profileSessions.active.value?.graph?.scope?.coroutineScope?.cancel() },
            processes = DesktopChildProcesses(graph.dispatchers),
        )?.let { throw it }
    }
    cleanup.invokeOnCompletion { failure ->
        if (failure == null) completed.complete(Unit) else completed.completeExceptionally(failure)
    }
    try {
        completed.get(FAILED_LAUNCH_SHUTDOWN_SECONDS, TimeUnit.SECONDS)
    } finally {
        scope.cancel()
    }
}

private const val FAILED_LAUNCH_SHUTDOWN_SECONDS = 180L
