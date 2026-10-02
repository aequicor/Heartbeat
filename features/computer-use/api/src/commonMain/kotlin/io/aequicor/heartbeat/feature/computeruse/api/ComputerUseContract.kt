package io.aequicor.heartbeat.feature.computeruse.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState

/** Profile machine state of the computer use feature. */
public sealed interface ComputerUseState : MachineState {
    /** Not probed yet; nothing is captured. */
    public data object Idle : ComputerUseState

    /** Probing the platform and the operating system permissions. */
    public data object Checking : ComputerUseState

    /** The host refuses capture or input; [blockers] name what the user has to change. */
    public data class Unavailable(public val blockers: List<ComputerUseBlocker>) : ComputerUseState

    /** Available, no capture is running. */
    public data class Ready(
        public val capabilities: ComputerUseCapabilities,
        public val targets: List<WindowTarget> = emptyList(),
        public val isInputArmed: Boolean = false,
        public val lastPreview: CaptureRef? = null,
        /**
         * Agent turns the user stopped while capturing; BeginCapture refuses them. Kept only while the machine
         * stays in Ready/Capturing: the profile's tools refuse those turns for the rest of the turn in any state.
         */
        public val stoppedOwners: Set<CaptureOwner.Agent> = emptySet(),
    ) : ComputerUseState

    /** One capture session is open; its master frames are addressable by [CaptureId]. */
    public data class Capturing(
        public val session: CaptureSessionId,
        public val mode: ComputerUseMode,
        public val owner: CaptureOwner,
        public val capabilities: ComputerUseCapabilities,
        public val targets: List<WindowTarget> = emptyList(),
        public val isInputArmed: Boolean = false,
        public val master: CaptureRef? = null,
        public val lastPreview: CaptureRef? = null,
        public val lastCrop: CaptureRef? = null,
        public val frameCount: Long = 0L,
        public val isOpen: Boolean = false,
        /** Actual input feedback and current target geometry, independent of the settings screen. */
        public val inputActivity: ComputerUseInputActivity = ComputerUseInputActivity(),
        /** Agent turns the user stopped while capturing; carried into Ready, see [Ready.stoppedOwners]. */
        public val stoppedOwners: Set<CaptureOwner.Agent> = emptySet(),
    ) : ComputerUseState

    /** The session ended because of an error; [Public.Retry][ComputerUseIntent.Public.Retry] probes again. */
    public data class Failed(public val reason: ComputerUseFailure, public val session: CaptureSessionId? = null) :
        ComputerUseState
}

/** Public commands and private host results. */
public sealed interface ComputerUseIntent : MachineIntent {
    /** What the settings screen, the host chrome, other features and the hosted tools may send. */
    public sealed interface Public : ComputerUseIntent {
        /** Probes availability; the first command after the profile started. */
        public data object Start : Public

        /** Probes again after a refusal or a failure, e.g. once a permission was granted. */
        public data object Retry : Public

        /** Re-reads the window list. */
        public data object RefreshTargets : Public

        /**
         * Opens a capture session. The caller allocates [session], so transitions stay deterministic.
         * Rejected when [mode] is not covered by the probed capabilities or the user stopped [owner]'s turn.
         */
        public data class BeginCapture(
            public val mode: ComputerUseMode,
            public val owner: CaptureOwner,
            public val session: CaptureSessionId,
        ) : Public

        /**
         * Replaces the captured target of the running session; its master frames become unreachable. A named
         * [owner] must already own the session: a capture is never handed over to another turn.
         */
        public data class SwitchMode(
            public val mode: ComputerUseMode,
            public val session: CaptureSessionId,
            public val owner: CaptureOwner? = null,
            public val expectedSession: CaptureSessionId? = null,
        ) : Public

        /** Ends the running session, disarms input and purges its master frames. */
        public data object EndCapture : Public

        /**
         * The owner's turn ended: ends the running session when it belongs to [owner] and forgets a stop of that
         * turn; another owner's session is untouched.
         */
        public data class OwnerReleased(public val owner: CaptureOwner) : Public

        /**
         * Enables or disables input after the host authorizes an action. Optional session/frame binding prevents
         * an authorization for one captured target from arming a replacement target.
         */
        public data class ArmInput(
            public val isArmed: Boolean,
            public val expectedSession: CaptureSessionId? = null,
            public val expectedCapture: CaptureId? = null,
            public val expectedOwner: CaptureOwner? = null,
        ) : Public

        /** Captures one frame of the running session; a named [expectedOwner] must own it. */
        public data class Capture(
            public val request: CaptureRequest,
            public val requestId: String? = null,
            public val expectedSession: CaptureSessionId? = null,
            public val expectedOwner: CaptureOwner? = null,
        ) : Public

        /** Cuts a region out of a stored master frame of the running session; a named [expectedOwner] must own it. */
        public data class Crop(
            public val request: CropRequest,
            public val requestId: String? = null,
            public val expectedSession: CaptureSessionId? = null,
            public val expectedOwner: CaptureOwner? = null,
        ) : Public

        /** Applies one input action; rejected unless input is armed, the mode allows it and the binding matches. */
        public data class Input(
            public val action: InputAction,
            public val requestId: String? = null,
            public val expectedSession: CaptureSessionId? = null,
            public val expectedCapture: CaptureId? = null,
            public val expectedOwner: CaptureOwner? = null,
        ) : Public

        /** Cancels only the named session, fencing late tool timeouts from newer sessions and other owners. */
        public data class CancelSession(
            public val session: CaptureSessionId,
            public val expectedOwner: CaptureOwner? = null,
        ) : Public

        /**
         * The user stops [owner]'s agent turn while it captures: the capture ends, and the turn can neither open
         * another capture nor use computer tools again while its other tools keep working. The host offers the stop
         * only during a capture; a stop that arrives after the capture ended is ignored rather than outliving it.
         */
        public data class StopAgent(public val owner: CaptureOwner.Agent) : Public

        /** Kill switch: stops everything from any state and returns to [ComputerUseState.Idle]. */
        public data object Revoke : Public
    }

    /** Results reported by the effect handler. */
    public sealed interface Internal : ComputerUseIntent {
        /** Cleanup completed for this exact session. */
        public data class SessionClosed(
            public val session: CaptureSessionId,
            public val reason: ComputerUseFailure? = null,
        ) : Internal

        /** The host opened this session; capture/input may now run. */
        public data class CaptureOpened(public val session: CaptureSessionId) : Internal

        /** Native input or target observation; a replaced session's feedback is ignored. */
        public data class InputProgress(
            public val session: CaptureSessionId,
            public val activity: ComputerUseInputActivity,
        ) : Internal

        /** The host can work; [capabilities] drive every later guard. */
        public data class Available(public val capabilities: ComputerUseCapabilities) : Internal

        /** The host refuses; [blockers] say what the user has to change. */
        public data class Blocked(public val blockers: List<ComputerUseBlocker>) : Internal

        /** A fresh window list. */
        public data class TargetsLoaded(public val targets: List<WindowTarget>) : Internal

        /** One frame was captured and stored: the master artifact and the frame handed to the caller. */
        public data class FrameCaptured(
            public val master: CaptureRef,
            public val preview: CaptureRef,
            public val tiles: TileGrid? = null,
            public val requestId: String? = null,
        ) : Internal

        /** One crop of a stored master frame was produced. */
        public data class CropProduced(
            public val crop: CaptureRef,
            public val requestId: String? = null,
            public val master: CaptureRef? = null,
        ) : Internal

        /** One input action reached the operating system. */
        public data class InputApplied(public val action: InputAction, public val requestId: String? = null) : Internal

        /** One request was refused; the session itself stays usable. */
        public data class Rejected(public val reason: ComputerUseFailure, public val requestId: String? = null) :
            Internal

        /** The captured target disappeared or a permission was revoked; the session cannot continue. */
        public data class CaptureLost(
            public val reason: ComputerUseFailure,
            public val session: CaptureSessionId? = null,
        ) : Internal

        /** Probing failed. */
        public data class Failed(public val reason: ComputerUseFailure) : Internal
    }
}

/** Host commands; every one of them performs IO in the feature implementation. */
public sealed interface ComputerUseEffect : MachineEffect {
    /** Probes platform support and operating system permissions. */
    public data object ProbeAvailability : ComputerUseEffect

    /** Enumerates the capturable windows. */
    public data object EnumerateWindows : ComputerUseEffect

    /** Opens the capture device for [mode] of [session]; leaving the capturing state cancels it. */
    public data class OpenCapture(public val mode: ComputerUseMode, public val session: CaptureSessionId) :
        ComputerUseEffect

    /** Observes target moves, resizing and focus while this session remains open. */
    public data class ObserveCapture(public val session: CaptureSessionId) : ComputerUseEffect

    /** Releases the capture device. */
    public data class CloseCapture(
        public val session: CaptureSessionId? = null,
        public val reason: ComputerUseFailure? = null,
    ) : ComputerUseEffect

    /** Captures one frame and stores its master and preview artifacts. */
    public data class CaptureFrame(public val request: CaptureRequest, public val requestId: String? = null) :
        ComputerUseEffect

    /** Cuts a region out of a stored master frame. */
    public data class ProduceCrop(public val request: CropRequest, public val requestId: String? = null) :
        ComputerUseEffect

    /** Applies one input action. */
    public data class ApplyInput(
        public val action: InputAction,
        public val requestId: String? = null,
        public val expectedCapture: CaptureId? = null,
    ) : ComputerUseEffect

    /** Deletes the master frames of the finished session. */
    public data class PurgeMasters(public val session: CaptureSessionId? = null) : ComputerUseEffect

    /** Refuses every later computer tool call of [owner]'s turn; recorded before the next intent is handled. */
    public data class StopOwner(public val owner: CaptureOwner.Agent) : ComputerUseEffect
}

/** One-shot events for the hosted tools and for other features. */
public sealed interface ComputerUseOutput : MachineOutput {
    /** The stored frames of this session have been removed. */
    public data class SessionClosed(
        public val session: CaptureSessionId,
        public val reason: ComputerUseFailure? = null,
    ) : ComputerUseOutput

    /** The captured target changed; `null` means "nothing is captured". */
    public data class CaptureChanged(public val mode: ComputerUseMode?) : ComputerUseOutput

    /** A frame is stored and can be read by its reference. */
    public data class FrameReady(
        public val capture: CaptureRef,
        public val tiles: TileGrid? = null,
        public val requestId: String? = null,
        public val master: CaptureRef? = null,
    ) : ComputerUseOutput

    /** One request was refused. */
    public data class Rejected(public val reason: ComputerUseFailure, public val requestId: String? = null) :
        ComputerUseOutput

    /** The user has to grant a permission before capture can start. */
    public data class PermissionRequired(public val blockers: List<ComputerUseBlocker>) : ComputerUseOutput

    /** One input action was applied. */
    public data class InputApplied(public val action: InputAction, public val requestId: String? = null) :
        ComputerUseOutput

    /** The kill switch disarmed capture/input and requested cleanup; [SessionClosed] confirms frame deletion. */
    public data object Revoked : ComputerUseOutput
}

/** Address of the profile machine; the only way to reach it from other features. */
public object ComputerUseMachineKey :
    MachineKey<
        ComputerUseState,
        ComputerUseIntent,
        ComputerUseIntent.Public,
        ComputerUseEffect,
        ComputerUseOutput,
    > {
    override val name: String = "computer-use"
}

/** The region this crop addresses in master pixels, or `null` when it leaves the master frame. */
public fun CropRequest.regionIn(master: CaptureRef): CaptureRegion? {
    val bounds = CaptureRegion(0, 0, master.masterWidthPx, master.masterHeightPx)
    val requested = normalized?.toRegion(master.masterWidthPx, master.masterHeightPx) ?: region ?: bounds
    val clipped = requested.intersect(bounds) ?: return null
    return if (clipped == requested) clipped else null
}

/** `true` when the probed capabilities cover [mode]. */
public fun ComputerUseCapabilities.supports(mode: ComputerUseMode): Boolean = when (mode) {
    is ComputerUseMode.Desktop -> isCaptureAvailable
    is ComputerUseMode.Window -> isCaptureAvailable && isWindowCaptureAvailable
}

/**
 * `true` when input may be applied in [mode]: a window session confines input to the captured window, while
 * desktop-wide input additionally requires the host's operating system permission.
 */
public fun ComputerUseCapabilities.allowsInput(mode: ComputerUseMode?): Boolean {
    if (!isInputAvailable || mode == null) return false
    return when (mode) {
        is ComputerUseMode.Window -> true
        is ComputerUseMode.Desktop -> isDesktopInputAllowed
    }
}
