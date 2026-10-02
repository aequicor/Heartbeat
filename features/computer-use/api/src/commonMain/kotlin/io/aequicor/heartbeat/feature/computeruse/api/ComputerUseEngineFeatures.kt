package io.aequicor.heartbeat.feature.computeruse.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess

/**
 * Computer control owned by Heartbeat: the only implementation that touches the real screen, mouse and
 * keyboard. Resolved from the profile graph by engine adapters and by the hosted `computer_*` tools, so an
 * adapter never invents its own capture path.
 *
 * Every operation revalidates its conditions: operating system permissions can be revoked while a session is
 * open, and the result is then [ComputerUseFailure.PermissionLost] instead of a stale frame.
 */
public interface HostComputerControl : EngineFeature {
    /** Availability and the active capture, without performing IO. */
    public suspend fun status(): ComputerUseStatus

    /** Capturable windows of the current desktop session; empty when window capture is unavailable. */
    public suspend fun windows(): List<WindowTarget>

    /** Resolves an explicit identity, including a host-owned window so callers can explain its refusal. */
    public suspend fun resolveWindow(id: WindowId): WindowTarget? = windows().firstOrNull { it.id == id }

    /** Captures one frame; the returned reference points at a stored artifact, never at raw pixels. */
    public suspend fun capture(request: CaptureRequest): CaptureResult

    /** Cuts a region out of an already stored master frame, optionally upscaled by [CropRequest.scale]. */
    public suspend fun crop(request: CropRequest): CaptureResult

    /** Applies one input action; refusals are returned, not thrown. */
    public suspend fun input(action: InputAction): InputOutcome

    /** Stops the active capture, disarms input and deletes the master frames of its session. */
    public suspend fun revoke()

    /** Typed key of the host capability. */
    public companion object : EngineFeatureKey<HostComputerControl>(
        EngineFeatureId("computer.host"),
        HostComputerControl::class,
    )
}

/**
 * Computer control implemented by an engine or its provider, when it has one. Declared through
 * `EngineDescriptor.declaredFeatures` and resolved from [EngineFeatures]; the host router prefers it while
 * `computer_use.native_routing` is on and falls back to [HostComputerControl] on
 * [FeatureAccess.Unavailable] and on any failure.
 */
public interface NativeComputerControl : EngineFeature {
    /**
     * Captures one frame with engine-side means.
     *
     * Host routing requests the full virtual desktop at native resolution with [CapturePresets.Master],
     * without a region or tile, then derives the requested preview from that master exactly once.
     * Window and individual-monitor captures use the host because this contract carries no target identity.
     */
    public suspend fun capture(request: CaptureRequest): NativeCapture

    /** Applies one input action with engine-side means. */
    public suspend fun input(action: InputAction): InputOutcome

    /** Typed key of the engine-native capability. */
    public companion object : EngineFeatureKey<NativeComputerControl>(
        EngineFeatureId("computer.control"),
        NativeComputerControl::class,
    )
}

/**
 * One captured frame produced by the host.
 *
 * [reference] addresses the stored artifact and is safe to log; [master] is the full-resolution frame it was
 * derived from and the only valid source of a later crop; [preview] carries the encoded bytes for a consumer
 * that attaches them to a prompt as `ContentPart.Image`, and is never logged.
 */
public data class CaptureResult(
    public val reference: CaptureRef? = null,
    public val master: CaptureRef? = null,
    public val tiles: TileGrid? = null,
    public val preview: EncodedFrame? = null,
    public val failure: ComputerUseFailure? = null,
) {
    /** `true` when the capture produced a usable frame. */
    public val isUsable: Boolean get() = failure == null && reference != null

    override fun toString(): String =
        "CaptureResult(reference=${reference?.id?.value.orEmpty()}, tiles=${tiles?.columns ?: 0}, failure=${failure?.name.orEmpty()})"
}

/** Encoded pixels of one frame; content is user data and must never be logged. */
// ByteArray content needs structural equality and a redacted toString rather than generated data methods.
@Suppress("UseDataClass")
public class EncodedFrame(
    public val format: CaptureFormat,
    public val widthPx: Int,
    public val heightPx: Int,
    public val content: ByteArray,
) {
    override fun toString(): String = "EncodedFrame(format=$format, ${widthPx}x$heightPx, bytes=${content.size})"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedFrame) return false
        return format == other.format &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = HASH_PRIME * result + widthPx
        result = HASH_PRIME * result + heightPx
        result = HASH_PRIME * result + content.contentHashCode()
        return result
    }

    private companion object {
        const val HASH_PRIME = 31
    }
}

/** One frame captured by an engine; the content is encoded, never raw pixels. */
// Generated data methods would expose frame content; the transport intentionally has identity semantics.
@Suppress("UseDataClass")
public class NativeCapture(
    public val format: CaptureFormat,
    public val widthPx: Int,
    public val heightPx: Int,
    public val content: ByteArray,
) {
    override fun toString(): String = "NativeCapture(format=$format, ${widthPx}x$heightPx, bytes=${content.size})"
}
