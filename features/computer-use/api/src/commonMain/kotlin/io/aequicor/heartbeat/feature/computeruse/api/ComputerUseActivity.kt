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

/**
 * Host-coordinate feedback that drives the virtual cursor; the pointer is relative to [bounds], not to a preview or
 * the user's mouse. The cursor is shown in every capture mode: over a window only while it is in the foreground.
 */
public data class ComputerUseInputActivity(
    public val bounds: ScreenBounds? = null,
    public val pointer: FramePoint? = null,
    public val isPointerVisible: Boolean = false,
    /** Increments once when an action first reaches the native input driver. */
    public val sequence: Long = 0L,
    /** Monotonic feedback revision; rejects a delayed observation after a newer pointer step. */
    public val revision: Long = 0L,
    /** What the agent is doing: gliding to a point, then pressing, dragging, scrolling or typing there. */
    public val gesture: ComputerUseGesture? = null,
    /** Increments once when the [gesture]'s effect (press, drag start, wheel, typing) reaches the driver. */
    public val effectSequence: Long = 0L,
)

/** The current input gesture of the agent, as the virtual cursor shows it. Typed text is never carried. */
public sealed interface ComputerUseGesture {
    /** The pointer glides towards its target. */
    public data object Move : ComputerUseGesture

    /** [count] clicks of [button] at the pointer. */
    public data class Press(public val button: MouseButton, public val count: Int) : ComputerUseGesture

    /** [button] is held while the pointer moves. */
    public data class Drag(public val button: MouseButton) : ComputerUseGesture

    /** Wheel notches at the pointer; negative scrolls up. */
    public data class Scroll(public val notches: Int) : ComputerUseGesture

    /** [length] UTF-16 code units are typed into the focused control. */
    public data class Type(public val length: Int) : ComputerUseGesture

    /** A key combination by its names, e.g. `["ctrl", "s"]`. */
    public data class Keys(public val names: List<String>) : ComputerUseGesture
}

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
