package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateMachineKey
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateState
import io.aequicor.heartbeat.platform.dibundle.AppStartupAccessors
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

private val log = Log.tag("Desktop")

/** Starts the app-scoped work of features once per launch (main thread); a failing one does not stop the others. */
internal fun startAppWork(graph: HeartbeatGraph) {
    (graph as AppStartupAccessors).appStartups.forEach { startup ->
        try {
            startup.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "app startup failed: ${startup::class.simpleName.orEmpty()}" }
        }
    }
}

/**
 * Quits the way the user would once the app update has started its installer: the installer waits for this process
 * to end before it replaces the app and starts the new version. The update feature decides when; the host only quits.
 */
@Composable
internal fun QuitForAppUpdate(machines: MachineRegistry, quit: () -> Unit) {
    val currentQuit by rememberUpdatedState(quit)
    LaunchedEffect(machines) {
        val updates = machines.observe(AppUpdateMachineKey).filterNotNull().first()
        updates.state.first { it is AppUpdateState.Restarting }
        log.i { "quitting for the app update" }
        currentQuit()
    }
}
