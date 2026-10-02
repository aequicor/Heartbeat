package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.EncodedFrame
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.FrameSpace
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget

/** One captured frame in memory, in physical screen pixels. */
internal data class RawFrame(val pixels: PixelGrid, val bounds: ScreenBounds, val capturedAtNanos: Long)

/** A pointer position in physical screen pixels. */
internal data class ScreenPoint(val x: Int, val y: Int)

/**
 * Captures the desktop or one window. Implementations live in the platform source sets; the common code only
 * ever sees pixels and geometry, never a platform handle.
 */
internal interface ScreenCapturer {
    /** Captures [region] of [mode] in physical pixels; `null` when the target is gone or refused. */
    suspend fun capture(mode: ComputerUseMode, region: CaptureRegion?, isCursorIncluded: Boolean = true): RawFrame?

    /** The current on-screen rectangle of [mode]; `null` when the target no longer exists. */
    suspend fun currentBounds(mode: ComputerUseMode): ScreenBounds?

    /** Explains a native geometry refusal before any presentation is hidden. */
    suspend fun failure(mode: ComputerUseMode): ComputerUseFailure? = null

    /** Adds a capture-local agent marker to a window raster; never samples the user's mouse. */
    fun markPointer(frame: RawFrame, point: FramePoint?): RawFrame = frame
}

/** Enumerates and activates capturable windows. */
internal interface WindowCatalog {
    /** `false` on hosts without window enumeration. */
    val isAvailable: Boolean

    /** Visible top-level windows, ordered front to back. */
    suspend fun list(): List<WindowTarget>

    /** The window with this identity, or `null` when it was closed. */
    suspend fun resolve(id: WindowId): WindowTarget?

    /** Brings the window to the front so that input reaches it; `false` when activation was refused. */
    suspend fun activate(target: WindowTarget): Boolean

    /** Typed activation refusal; platforms may verify foreground without attempting activation. */
    suspend fun activationFailure(target: WindowTarget): ComputerUseFailure? =
        if (activate(target)) null else ComputerUseFailure.ActivationFailed

    /** Validates the selected capture geometry before attempting activation. */
    suspend fun activationFailure(target: WindowTarget, isClientAreaOnly: Boolean): ComputerUseFailure? =
        activationFailure(target)

    /** Reads foreground state without moving focus. */
    suspend fun isForeground(target: WindowTarget): Boolean = true
}

/** Applies mouse and keyboard events. The host maps every point into physical screen pixels first. */
internal interface InputInjector {
    /** `false` on hosts without input injection or without the required permission. */
    val isAvailable: Boolean

    /**
     * Applies one action. [map] turns a caller-supplied point into screen pixels and returns `null` when the
     * point leaves the captured area, which the implementation must report as a refusal.
     */
    suspend fun apply(action: InputAction, map: (FramePoint) -> ScreenPoint?): InputOutcome

    /** Reports actual native pointer steps; null reports keyboard input without moving the pointer. */
    suspend fun applyObserved(
        action: InputAction,
        map: (FramePoint) -> ScreenPoint?,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ): InputOutcome = apply(action, map)
}

/** Operating system permissions and platform support. */
internal interface OsPermissions {
    /** Probes capture and input permissions; never throws, reports blockers instead. */
    suspend fun probe(): ComputerUseCapabilities

    /** Opens the system settings page for [blocker] when the host can. */
    suspend fun openSettings(blocker: ComputerUseBlocker)
}

/** Encodes and decodes frames; the platform source sets own the actual codecs. */
internal interface FrameEncoder {
    /** Encodes [frame] exactly as [encoding] asks; `null` when the codec is unavailable. */
    suspend fun encode(frame: PixelGrid, encoding: CaptureEncoding): EncodedFrame?

    /** Decodes an encoded frame back into pixels; `null` when the content is unusable. */
    suspend fun decode(content: ByteArray): PixelGrid?
}

/** Stores encoded frames under the application storage root. Paths never reach the logs. */
internal interface FrameStore {
    /** Writes [frame] and returns its absolute path. */
    suspend fun write(session: CaptureSessionId, id: CaptureId, frame: EncodedFrame): String

    /** Reads a stored frame; `null` when it was purged or expired. */
    suspend fun read(path: String): ByteArray?

    /** Deletes every frame of one session. */
    suspend fun delete(session: CaptureSessionId)
}

/** Pure geometry of one capture: where the frame is on screen and how its spaces map into each other. */
internal object CaptureRegionPolicy {
    /** The part of [requested] that lies inside [bounds]; `null` when they are disjoint. */
    fun clamp(requested: CaptureRegion, bounds: CaptureRegion): CaptureRegion? = requested.intersect(bounds)

    /** Pixel size of the preview frame derived from a `masterWidthPx x masterHeightPx` master. */
    fun previewSize(masterWidthPx: Int, masterHeightPx: Int, maxWidthPx: Int, maxHeightPx: Int): Pair<Int, Int> {
        if (maxWidthPx <= 0 && maxHeightPx <= 0) return masterWidthPx to masterHeightPx
        val byWidth = if (maxWidthPx <= 0) Double.MAX_VALUE else maxWidthPx.toDouble() / masterWidthPx
        val byHeight = if (maxHeightPx <= 0) Double.MAX_VALUE else maxHeightPx.toDouble() / masterHeightPx
        val factor = minOf(1.0, byWidth, byHeight)
        val width = (masterWidthPx * factor).toInt().coerceAtLeast(1)
        val height = (masterHeightPx * factor).toInt().coerceAtLeast(1)
        return even(width) to even(height)
    }

    /**
     * Maps a caller-supplied point into physical screen pixels.
     *
     * @return `null` when the point leaves the captured area: input outside the frame is refused instead of
     * being silently clamped, because a clamped click would hit an unrelated control.
     */
    // Geometry needs both frame dimensions and the host origin as one indivisible transform.
    @Suppress("LongParameterList")
    fun toScreen(
        point: FramePoint,
        space: FrameSpace,
        previewWidthPx: Int,
        previewHeightPx: Int,
        masterWidthPx: Int,
        masterHeightPx: Int,
        origin: ScreenBounds,
    ): ScreenPoint? {
        val master = toMaster(point, space, previewWidthPx, previewHeightPx, masterWidthPx, masterHeightPx)
            ?: return null
        return ScreenPoint(origin.x + master.x, origin.y + master.y)
    }

    /** Maps a caller-supplied point into master-frame pixels; `null` when it leaves the frame. */
    // Both axes of preview/master dimensions are required to validate the transform.
    @Suppress("LongParameterList")
    fun toMaster(
        point: FramePoint,
        space: FrameSpace,
        previewWidthPx: Int,
        previewHeightPx: Int,
        masterWidthPx: Int,
        masterHeightPx: Int,
    ): ScreenPoint? {
        val isHorizontalValid = point.x.isFinite() && point.x >= 0
        val isVerticalValid = point.y.isFinite() && point.y >= 0
        if (!isHorizontalValid || !isVerticalValid) return null
        val scaled = when (space) {
            FrameSpace.Preview -> {
                if (previewWidthPx <= 0 || previewHeightPx <= 0) return null
                val factorX = masterWidthPx.toDouble() / previewWidthPx
                val factorY = masterHeightPx.toDouble() / previewHeightPx
                point.x * factorX to point.y * factorY
            }

            FrameSpace.Master -> point.x to point.y

            FrameSpace.Normalized -> point.x * masterWidthPx to point.y * masterHeightPx

            FrameSpace.Screen -> point.x to point.y
        }
        val x = scaled.first.toInt()
        val y = scaled.second.toInt()
        if (!isInside(x, y, masterWidthPx, masterHeightPx)) return null
        return ScreenPoint(x, y)
    }

    /** `true` when a master-frame point lies inside the frame. */
    fun isInside(x: Int, y: Int, widthPx: Int, heightPx: Int): Boolean {
        val isHorizontal = x in 0 until widthPx
        val isVertical = y in 0 until heightPx
        return isHorizontal && isVertical
    }

    /** Maps master-frame pixels into physical screen pixels. */
    fun masterToScreen(point: ScreenPoint, origin: ScreenBounds): ScreenPoint =
        ScreenPoint(origin.x + point.x, origin.y + point.y)

    /** The master-frame rectangle a screen rectangle covers, clipped to the captured area. */
    fun screenToMaster(bounds: ScreenBounds, origin: ScreenBounds): CaptureRegion = CaptureRegion(
        x = (bounds.x - origin.x).coerceAtLeast(0),
        y = (bounds.y - origin.y).coerceAtLeast(0),
        widthPx = bounds.widthPx.coerceAtLeast(1),
        heightPx = bounds.heightPx.coerceAtLeast(1),
    )

    private fun even(value: Int): Int = if (value < 2) 2 else value - value % 2
}
