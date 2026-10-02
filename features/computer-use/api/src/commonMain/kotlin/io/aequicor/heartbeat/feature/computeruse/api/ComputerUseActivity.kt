package io.aequicor.heartbeat.feature.computeruse.api

/** Host presentation of an agent-owned capture, independent of the settings screen. */
public data class ComputerUseActivity(
    /** The host shows the agent's session above other applications while it opens or uses a capture. */
    public val isActive: Boolean = false,
    /** Monitor perimeters to shade; empty when only an application window is captured. */
    public val screens: List<ComputerUseScreenBounds> = emptyList(),
)

/** Monitor bounds in the coordinates the host reported in [MonitorInfo.bounds], including negative origins. */
public data class ComputerUseScreenBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** Agent capture pins the session before the first frame; only an open desktop capture shades the screen. */
public fun ComputerUseState.computerUseActivity(): ComputerUseActivity {
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
