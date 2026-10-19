package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.nio.file.Path
import java.util.ResourceBundle
import kotlin.time.Duration.Companion.seconds

/** Monitor-only UI: no application graph, profile stores, remote code execution or approval commands. */
internal fun showWorkerMonitor(root: Path, dispatchers: DispatcherProvider, instance: DesktopInstance? = null) {
    val labels = ResourceBundle.getBundle("worker")
    application {
        Window(onCloseRequest = ::exitApplication, title = labels.getString("title")) {
            DisposableEffect(window, instance) {
                val activation = instance?.onActivate {
                    window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                    window.isVisible = true
                    window.toFront()
                    window.requestFocus()
                }
                onDispose { activation?.close() }
            }
            HbTheme { WorkerMonitorContent(root, labels, dispatchers) }
        }
    }
}

@Composable
private fun WorkerMonitorContent(root: Path, labels: ResourceBundle, dispatchers: DispatcherProvider) {
    val observation by rememberWorkerObservation(root, dispatchers)
    var isStopping by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = observation.snapshot
    Column(
        modifier = Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop)
            .hbVerticalScroll(rememberScrollState()).padding(HbTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(HbTheme.spacing.m),
    ) {
        HbText(labels.getString("title"), style = HbTheme.typography.title)
        HbText(labels.getString("description"))
        WorkerMonitorStatus(observation, labels)
        HbText(labels.getString("handover"))
        HbButton(
            text = labels.getString("stop"),
            enabled =
                !isStopping && !observation.isUnavailable && current != null && current.phase != WorkerPhase.Stopping,
            onClick = {
                isStopping = true
                scope.launch {
                    isStopping = requestWorkerStop(root, dispatchers)
                }
            },
        )
    }
}

private data class WorkerObservation(val snapshot: WorkerSnapshot? = null, val isUnavailable: Boolean = false)

@Composable
private fun rememberWorkerObservation(
    root: Path,
    dispatchers: DispatcherProvider,
): androidx.compose.runtime.State<WorkerObservation> =
    androidx.compose.runtime.produceState(WorkerObservation(), root) {
        var isReported = false
        while (isActive) {
            try {
                val current = withContext(dispatchers.io) { workerRequest(root, "status") }
                value = WorkerObservation(current, current == null)
                isReported = false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isReported) Log.tag("WorkerMonitor").w(error) { "Execution owner status is unavailable" }
                isReported = true
                value = value.copy(isUnavailable = true)
            }
            delay(2.seconds)
        }
    }

@Composable
private fun WorkerMonitorStatus(observation: WorkerObservation, labels: ResourceBundle) {
    val current = observation.snapshot
    if (observation.isUnavailable || current == null) {
        HbText(labels.getString("unavailable"))
    } else {
        HbText(labels.getString("phase.${current.phase.name}"))
        if (current.problem != WorkerProblem.None) HbText(labels.getString("problem.${current.problem.name}"))
        HbText("${labels.getString("workflows")}: ${current.workflows}")
        HbText("${labels.getString("decisions")}: ${current.decisions}")
    }
}

private suspend fun requestWorkerStop(root: Path, dispatchers: DispatcherProvider): Boolean = try {
    withContext(dispatchers.io) { workerRequest(root, "stop") }
    true
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Log.tag("WorkerMonitor").e(error) { "Worker stop request failed" }
    false
}
