package io.aequicor.heartbeat.feature.computeruse.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.jvm.JvmInline

/** Opaque platform window handle (`HWND` on Windows, `CGWindowID` on macOS). */
@JvmInline
public value class WindowId(public val value: String)

/** Opaque monitor identity assigned by the host. */
@JvmInline
public value class MonitorId(public val value: String)

/** Identity of one stored capture artifact (a master frame or a derived frame). */
@JvmInline
public value class CaptureId(public val value: String)

/** Identity of one capture session; masters of a session are purged together. */
@JvmInline
public value class CaptureSessionId(public val value: String)

/**
 * Rectangle in the operating system coordinate space of the virtual desktop.
 * Master frames use physical pixels; input maps them proportionally into these host coordinates.
 *
 * @property x left edge, in virtual-desktop pixels; negative on monitors left of the primary one.
 * @property y top edge, in virtual-desktop pixels.
 * @property widthPx width in physical pixels; positive.
 * @property heightPx height in physical pixels; positive.
 * @property scale monitor scale factor the rectangle was measured with; `1.0` when unknown.
 */
public data class ScreenBounds(
    public val x: Int,
    public val y: Int,
    public val widthPx: Int,
    public val heightPx: Int,
    public val scale: Double = 1.0,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "ScreenBounds must have a positive size: ${widthPx}x$heightPx" }
        require(scale > 0.0) { "ScreenBounds scale must be positive: $scale" }
    }

    /** Right edge, exclusive. */
    public val right: Int get() = x + widthPx

    /** Bottom edge, exclusive. */
    public val bottom: Int get() = y + heightPx

    /** The same rectangle as a frame-local region. */
    public fun region(): CaptureRegion = CaptureRegion(x, y, widthPx, heightPx)
}

/** Rectangle inside one captured frame, in that frame's pixels. */
public data class CaptureRegion(
    public val x: Int,
    public val y: Int,
    public val widthPx: Int,
    public val heightPx: Int,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "CaptureRegion must have a positive size: ${widthPx}x$heightPx" }
        require(x >= 0 && y >= 0) { "CaptureRegion origin must not be negative: $x,$y" }
    }

    /** Total pixel count. */
    public val areaPx: Long get() = widthPx.toLong() * heightPx.toLong()

    /** `true` when this region lies completely inside [bounds]. */
    public fun isInside(bounds: CaptureRegion): Boolean =
        x >= bounds.x && y >= bounds.y && right <= bounds.right && bottom <= bounds.bottom

    /** Right edge, exclusive. */
    public val right: Int get() = x + widthPx

    /** Bottom edge, exclusive. */
    public val bottom: Int get() = y + heightPx

    /** The intersection with [other], or `null` when the regions are disjoint. */
    public fun intersect(other: CaptureRegion): CaptureRegion? {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val rightEdge = minOf(right, other.right)
        val bottomEdge = minOf(bottom, other.bottom)
        return if (rightEdge > left && bottomEdge > top) {
            CaptureRegion(left, top, rightEdge - left, bottomEdge - top)
        } else {
            null
        }
    }
}

/** A point inside a captured frame, in the coordinate space named by [FrameSpace]. */
public data class FramePoint(public val x: Double, public val y: Double)

/**
 * Coordinate space of agent-supplied geometry.
 *
 * An agent normally sees a downscaled preview, so its coordinates are [Preview] by default; the host maps them
 * through the master frame into screen pixels and rejects anything outside the captured region.
 */
public enum class FrameSpace {
    /** Pixels of the preview frame returned to the caller. */
    Preview,

    /** Pixels of the stored full-resolution master frame. */
    Master,

    /** Fractions of the captured area, `0.0..1.0`. */
    Normalized,

    /** Operating system coordinates of the virtual desktop (points on macOS). */
    Screen,
}

/** One physical monitor of the virtual desktop. */
public data class MonitorInfo(
    public val id: MonitorId,
    public val bounds: ScreenBounds,
    public val isPrimary: Boolean,
) {
    override fun toString(): String = "MonitorInfo(id=$id, bounds=$bounds, primary=$isPrimary)"
}

/** A capturable top-level window. The title is user data: it is never logged. */
public data class WindowTarget(
    public val id: WindowId,
    public val application: String,
    public val title: String,
    public val bounds: ScreenBounds,
    public val isMinimized: Boolean = false,
    public val revision: Long = 0L,
) {
    override fun toString(): String = "WindowTarget(id=$id, minimized=$isMinimized, revision=$revision)"
}

/** What is captured: the whole desktop or one window. */
public sealed interface ComputerUseMode {
    /**
     * The whole virtual desktop, or one [monitor] when it is set.
     *
     * @property monitor the captured monitor; `null` captures every monitor.
     * @property isCursorIncluded draw the mouse pointer into the frame.
     */
    public data class Desktop(public val monitor: MonitorId? = null, public val isCursorIncluded: Boolean = true) :
        ComputerUseMode

    /**
     * A single window.
     *
     * @property target the window to capture; its identity is re-resolved before every frame and input action.
     * @property isClientAreaOnly capture the client area without the title bar and frame.
     */
    public data class Window(public val target: WindowTarget, public val isClientAreaOnly: Boolean = false) :
        ComputerUseMode {
        override fun toString(): String = "Window(id=${target.id}, clientAreaOnly=$isClientAreaOnly)"
    }
}

/** Who owns an active capture; the capture ends together with its owner. */
public sealed interface CaptureOwner {
    /** A Heartbeat screen; no longer created since the panel was removed, see issue #162. */
    public data object Panel : CaptureOwner

    /** One agent turn of one session. */
    public data class Agent(public val session: SessionRef, public val turn: TurnId) : CaptureOwner {
        override fun toString(): String = "Agent(turn=$turn)"
    }
}

/** Mouse button of a pointer action. */
public enum class MouseButton { Left, Right, Middle }

/** One input action. Pointer coordinates live in [space] of the frame the caller received. */
public sealed interface InputAction {
    /** Coordinate space of the action's points; `null` for actions without points. */
    public val space: FrameSpace?

    /** Moves the pointer without pressing a button. */
    public data class MoveTo(public val point: FramePoint, override val space: FrameSpace = FrameSpace.Preview) :
        InputAction

    /** Clicks at a point. */
    public data class Click(
        public val point: FramePoint,
        public val button: MouseButton = MouseButton.Left,
        public val count: Int = 1,
        override val space: FrameSpace = FrameSpace.Preview,
    ) : InputAction

    /** Drags between two points with one button held. */
    public data class Drag(
        public val from: FramePoint,
        public val to: FramePoint,
        public val button: MouseButton = MouseButton.Left,
        override val space: FrameSpace = FrameSpace.Preview,
    ) : InputAction

    /** Scrolls at a point; [deltaY] is negative when scrolling up. */
    public data class Scroll(
        public val point: FramePoint,
        public val deltaX: Int = 0,
        public val deltaY: Int = 0,
        override val space: FrameSpace = FrameSpace.Preview,
    ) : InputAction

    /** Types text. Characters without a key mapping are rejected instead of being silently dropped. */
    public data class Type(public val text: String) : InputAction {
        override val space: FrameSpace? = null

        override fun toString(): String = "Type(length=${text.length})"
    }

    /** Presses a key combination, e.g. `["ctrl", "s"]` or `["enter"]`. */
    public data class Key(public val keys: List<String>) : InputAction {
        override val space: FrameSpace? = null
    }
}

/** Result of one input action. */
public sealed interface InputOutcome {
    /** The action reached the operating system. */
    public data object Applied : InputOutcome

    /** The action was refused; [reason] says why. */
    public data class Rejected(public val reason: ComputerUseFailure) : InputOutcome
}

/** A stored capture artifact. The path is host data: it is never logged. */
public data class CaptureRef(
    public val id: CaptureId,
    public val session: CaptureSessionId,
    public val format: CaptureFormat,
    public val widthPx: Int,
    public val heightPx: Int,
    public val region: CaptureRegion,
    public val masterWidthPx: Int,
    public val masterHeightPx: Int,
    public val bytes: Long,
    public val estimatedTokens: Int,
    public val path: String,
    public val sequence: Long,
    public val isMaster: Boolean = false,
) {
    override fun toString(): String =
        "CaptureRef(id=$id, ${widthPx}x$heightPx, format=$format, bytes=$bytes, tokens=$estimatedTokens)"

    /** Scale of this frame relative to the master frame it was derived from. */
    public val previewScale: Double
        get() = if (masterWidthPx <= 0) 1.0 else widthPx.toDouble() / region.widthPx.toDouble()
}

/** A tile grid over one master frame; agents request a tile instead of inventing coordinates. */
public data class TileGrid(
    public val columns: Int,
    public val rows: Int,
    public val tileWidthPx: Int,
    public val tileHeightPx: Int,
    public val overlapPx: Int,
) {
    init {
        require(columns > 0 && rows > 0) { "TileGrid must have at least one tile: ${columns}x$rows" }
        require(overlapPx >= 0) { "TileGrid overlap must not be negative: $overlapPx" }
    }

    /** The master-frame region of tile `column:row`, or `null` when the tile does not exist. */
    public fun region(column: Int, row: Int, masterWidthPx: Int, masterHeightPx: Int): CaptureRegion? {
        if (column !in 0 until columns || row !in 0 until rows) return null
        val x = column * (tileWidthPx - overlapPx)
        val y = row * (tileHeightPx - overlapPx)
        return CaptureRegion(
            x = minOf(x, (masterWidthPx - 1).coerceAtLeast(0)),
            y = minOf(y, (masterHeightPx - 1).coerceAtLeast(0)),
            widthPx = minOf(tileWidthPx, masterWidthPx - x).coerceAtLeast(1),
            heightPx = minOf(tileHeightPx, masterHeightPx - y).coerceAtLeast(1),
        )
    }

    /** Builds grids for master frames and answers tile requests. */
    public companion object {
        /** Builds a grid for a `masterWidthPx x masterHeightPx` frame with [overlapPx] shared edges. */
        public fun forMaster(
            masterWidthPx: Int,
            masterHeightPx: Int,
            tileWidthPx: Int = DEFAULT_TILE_PX,
            tileHeightPx: Int = DEFAULT_TILE_PX,
            overlapPx: Int = DEFAULT_OVERLAP_PX,
        ): TileGrid {
            require(masterWidthPx > 0 && masterHeightPx > 0) { "Master frame must have a positive size" }
            require(tileWidthPx > overlapPx && tileHeightPx > overlapPx) { "Tile must be larger than the overlap" }
            val columns = tiles(masterWidthPx, tileWidthPx, overlapPx)
            val rows = tiles(masterHeightPx, tileHeightPx, overlapPx)
            return TileGrid(columns, rows, tileWidthPx, tileHeightPx, overlapPx)
        }

        private const val DEFAULT_TILE_PX: Int = 1024
        private const val DEFAULT_OVERLAP_PX: Int = 64

        private fun tiles(sizePx: Int, tilePx: Int, overlapPx: Int): Int {
            val stride = tilePx - overlapPx
            return (((sizePx - overlapPx) + stride - 1) / stride).coerceAtLeast(1)
        }
    }
}

/** What the host can do right now; recomputed on every probe. */
public data class ComputerUseCapabilities(
    public val isCaptureAvailable: Boolean,
    public val isWindowCaptureAvailable: Boolean,
    public val isInputAvailable: Boolean,
    public val isDesktopInputAllowed: Boolean,
    public val monitors: List<MonitorInfo> = emptyList(),
    public val blockers: List<ComputerUseBlocker> = emptyList(),
)

/** Host availability plus the active capture; the value exposed to engines. */
public data class ComputerUseStatus(
    public val capabilities: ComputerUseCapabilities,
    public val mode: ComputerUseMode? = null,
    public val isInputArmed: Boolean = false,
    public val lastPreview: CaptureRef? = null,
) {
    override fun toString(): String =
        "ComputerUseStatus(mode=${mode?.let { it::class.simpleName }.orEmpty()}, armed=$isInputArmed)"
}

/** Why the operating system or the platform refuses capture or input. */
public enum class ComputerUseBlocker {
    UnsupportedPlatform,
    ScreenRecordingPermission,
    AccessibilityPermission,
    ElevationRequired,
    SessionLocked,
    Headless,
}

/** Typed failure of one operation; never carries native exception text. */
public enum class ComputerUseFailure {
    Unavailable,
    TargetClosed,
    TargetMinimized,
    CaptureFailed,
    EncodingTooLarge,
    InputRejected,
    UnsupportedCharacter,
    RegionOutOfBounds,
    CaptureExpired,
    UnknownCapture,
    CropTooLarge,
    ModeNotAllowed,
    NotArmed,
    PermissionLost,
    Timeout,
}
