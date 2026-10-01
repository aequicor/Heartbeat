package io.aequicor.heartbeat.platform.dibundle.root

import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState

/** Desktop presentation of an agent-owned capture, independent of the settings screen. */
data class ComputerUseActivity(
    /** Shows the compact session above other applications while the agent opens or uses a capture. */
    val isActive: Boolean = false,
    /** Monitor perimeters to shade; empty when only an application window is captured. */
    val screens: List<ComputerUseScreenBounds> = emptyList(),
)

/** Monitor bounds in the host's logical coordinates, including negative desktop origins. */
data class ComputerUseScreenBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** Agent capture pins the session before the first frame; only an open desktop capture shades the screen. */
internal fun ComputerUseState.computerUseActivity(): ComputerUseActivity {
    if (this !is ComputerUseState.Capturing || owner !is CaptureOwner.Agent) return ComputerUseActivity()
    val desktop = mode as? ComputerUseMode.Desktop
    val screens = if (desktop == null || !isOpen) {
        emptyList()
    } else {
        capabilities.monitors
            .filter { desktop.monitor == null || it.id == desktop.monitor }
            .map { monitor ->
                val bounds = monitor.bounds
                ComputerUseScreenBounds(bounds.x, bounds.y, bounds.widthPx, bounds.heightPx)
            }
    }
    return ComputerUseActivity(isActive = true, screens = screens)
}
