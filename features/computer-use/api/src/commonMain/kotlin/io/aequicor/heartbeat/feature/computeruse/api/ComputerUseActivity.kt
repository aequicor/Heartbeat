package io.aequicor.heartbeat.feature.computeruse.api

/** Host presentation of an agent-owned capture, independent of the settings screen. */
public data class ComputerUseActivity(
    /** The host shows the agent's session above other applications while it opens or uses a capture. */
    public val isActive: Boolean = false,
    /** Monitor perimeters to shade; empty when only an application window is captured. */
    public val screens: List<ComputerUseScreenBounds> = emptyList(),
    /** The agent turn holding the capture; the host stops it with [ComputerUseIntent.Public.StopAgent]. */
    public val owner: CaptureOwner.Agent? = null,
    public val session: CaptureSessionId? = null,
    /** Live native input feedback, fenced to the active capture session. */
    public val input: ComputerUseInputActivity = ComputerUseInputActivity(),
)

/** Host-coordinate feedback; the pointer is relative to [bounds], not to a preview or the user's mouse. */
public data class ComputerUseInputActivity(
    public val bounds: ScreenBounds? = null,
    public val pointer: FramePoint? = null,
    public val isPointerVisible: Boolean = false,
    /** Increments once when an action first reaches the native input driver. */
    public val sequence: Long = 0L,
    /** Monotonic feedback revision; rejects a delayed observation after a newer pointer step. */
    public val revision: Long = 0L,
)

/** Monitor bounds in the coordinates the host reported in [MonitorInfo.bounds], including negative origins. */
public data class ComputerUseScreenBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** Agent capture pins the session before the first frame; only an open desktop capture shades the screen. */
public fun ComputerUseState.computerUseActivity(): ComputerUseActivity {
    val capture = this as? ComputerUseState.Capturing
    val agent = capture?.owner as? CaptureOwner.Agent
    if (capture == null || agent == null) return ComputerUseActivity()
    val desktop = capture.mode as? ComputerUseMode.Desktop
    val screens = if (desktop == null || !capture.isOpen) {
        emptyList()
    } else {
        capture.capabilities.monitors
            .filter { desktop.monitor == null || it.id == desktop.monitor }
            .map { monitor ->
                val bounds = monitor.bounds
                ComputerUseScreenBounds(bounds.x, bounds.y, bounds.widthPx, bounds.heightPx)
            }
    }
    return ComputerUseActivity(
        isActive = true,
        screens = screens,
        owner = agent,
        session = capture.session,
        input = capture.inputActivity,
    )
}
