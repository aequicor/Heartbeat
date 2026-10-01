package io.aequicor.heartbeat.feature.computeruse.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Profile computer use workflow. Capture and input run through effects, so leaving [ComputerUseState.Capturing]
 * cancels them, and every refusal is reported instead of being swallowed.
 *
 * | From | Intent | Guard | To | Effect / Output |
 * |---|---|---|---|---|
 * | Idle | Start | | Checking | ProbeAvailability |
 * | Checking | Available | | Ready | EnumerateWindows |
 * | Checking | Blocked | | Unavailable | PermissionRequired |
 * | Checking | Failed | | Failed | |
 * | Unavailable / Failed / Ready | Retry | | Checking | ProbeAvailability |
 * | Ready | RefreshTargets | | stay | EnumerateWindows |
 * | Ready | TargetsLoaded | | stay(targets) | |
 * | Ready | ArmInput | input is available | stay(armed) | |
 * | Ready | BeginCapture | capabilities support the mode | Capturing | OpenCapture, CaptureChanged |
 * | Capturing | Capture | | stay | CaptureFrame |
 * | Capturing | Crop | master exists and holds the region | stay | ProduceCrop |
 * | Capturing | Input | armed and the mode allows input | stay | ApplyInput |
 * | Capturing | FrameCaptured | | stay(master, preview, frames+1) | FrameReady |
 * | Capturing | CropProduced | | stay(lastCrop) | FrameReady |
 * | Capturing | InputApplied | | stay | InputApplied output |
 * | Capturing | SwitchMode | capabilities support the mode | Capturing (re-entered) | OpenCapture, CaptureChanged |
 * | Capturing | EndCapture | | Ready(disarmed) | CloseCapture, PurgeMasters, CaptureChanged(null) |
 * | Capturing | OwnerReleased | same owner | Ready(disarmed) | CloseCapture, PurgeMasters, CaptureChanged(null) |
 * | Capturing | CaptureLost | | Failed | CloseCapture, CaptureChanged(null) |
 * | Capturing | Rejected / Failed | | stay | Rejected output |
 * | Capturing | RefreshTargets / TargetsLoaded | | stay(targets) | EnumerateWindows |
 * | any | Revoke | | Idle | CloseCapture, PurgeMasters, Revoked |
 *
 * Session identifiers come with the intents instead of being generated inside transitions, so the spec stays a
 * pure function and its tests compare states directly.
 */
public val ComputerUseMachineSpec: MachineSpec<
    ComputerUseState,
    ComputerUseIntent,
    ComputerUseEffect,
    ComputerUseOutput,
> = machineSpec(ComputerUseMachineKey, ComputerUseState.Idle) {
    state<ComputerUseState.Idle> {
        on<ComputerUseIntent.Public.Start> {
            goto<ComputerUseState.Checking> { ComputerUseState.Checking }
            effect { ComputerUseEffect.ProbeAvailability }
        }
    }
    state<ComputerUseState.Checking> {
        on<ComputerUseIntent.Internal.Available> {
            goto<ComputerUseState.Ready> { ComputerUseState.Ready(capabilities = intent.capabilities) }
            effect { ComputerUseEffect.EnumerateWindows }
        }
        on<ComputerUseIntent.Internal.Blocked> {
            goto<ComputerUseState.Unavailable> { ComputerUseState.Unavailable(intent.blockers) }
            output { ComputerUseOutput.PermissionRequired(intent.blockers) }
        }
        on<ComputerUseIntent.Internal.Failed> {
            goto<ComputerUseState.Failed> { ComputerUseState.Failed(intent.reason) }
        }
    }
    state<ComputerUseState.Unavailable> {
        on<ComputerUseIntent.Public.Retry> {
            goto<ComputerUseState.Checking> { ComputerUseState.Checking }
            effect { ComputerUseEffect.ProbeAvailability }
        }
    }
    state<ComputerUseState.Failed> {
        on<ComputerUseIntent.Public.Retry> {
            goto<ComputerUseState.Checking> { ComputerUseState.Checking }
            effect { ComputerUseEffect.ProbeAvailability }
        }
    }
    state<ComputerUseState.Ready> {
        on<ComputerUseIntent.Public.Retry> {
            goto<ComputerUseState.Checking> { ComputerUseState.Checking }
            effect { ComputerUseEffect.ProbeAvailability }
        }
        on<ComputerUseIntent.Public.RefreshTargets> { effect { ComputerUseEffect.EnumerateWindows } }
        on<ComputerUseIntent.Internal.TargetsLoaded> { stay { state.copy(targets = intent.targets) } }
        on<ComputerUseIntent.Public.ArmInput>(guard = { state.capabilities.isInputAvailable }) {
            stay { state.copy(isInputArmed = intent.isArmed) }
        }
        on<ComputerUseIntent.Public.BeginCapture>(guard = { state.capabilities.supports(intent.mode) }) {
            goto<ComputerUseState.Capturing> {
                ComputerUseState.Capturing(
                    session = intent.session,
                    mode = intent.mode,
                    owner = intent.owner,
                    capabilities = state.capabilities,
                    targets = state.targets,
                )
            }
            effect { ComputerUseEffect.OpenCapture(intent.mode, intent.session) }
            output { ComputerUseOutput.CaptureChanged(intent.mode) }
        }
    }
    state<ComputerUseState.Capturing> {
        on<ComputerUseIntent.Public.RefreshTargets> { effect { ComputerUseEffect.EnumerateWindows } }
        on<ComputerUseIntent.Internal.TargetsLoaded> { stay { state.copy(targets = intent.targets) } }
        on<ComputerUseIntent.Public.ArmInput>(guard = { state.capabilities.isInputAvailable }) {
            stay { state.copy(isInputArmed = intent.isArmed) }
        }
        on<ComputerUseIntent.Public.Capture> { effect { ComputerUseEffect.CaptureFrame(intent.request) } }
        on<ComputerUseIntent.Public.Crop>(guard = { state.canCrop(intent.request) }) {
            effect { ComputerUseEffect.ProduceCrop(intent.request) }
        }
        on<ComputerUseIntent.Public.Input>(
            guard = { state.isInputArmed && state.capabilities.allowsInput(state.mode) },
        ) { effect { ComputerUseEffect.ApplyInput(intent.action) } }
        on<ComputerUseIntent.Public.SwitchMode>(guard = { state.capabilities.supports(intent.mode) }) {
            goto<ComputerUseState.Capturing> {
                ComputerUseState.Capturing(
                    session = intent.session,
                    mode = intent.mode,
                    owner = state.owner,
                    capabilities = state.capabilities,
                    targets = state.targets,
                    isInputArmed = state.isInputArmed,
                )
            }
            effect { ComputerUseEffect.OpenCapture(intent.mode, intent.session) }
            output { ComputerUseOutput.CaptureChanged(intent.mode) }
        }
        on<ComputerUseIntent.Public.EndCapture> {
            goto<ComputerUseState.Ready> { state.asReady() }
            effect { ComputerUseEffect.CloseCapture }
            effect { ComputerUseEffect.PurgeMasters }
            output { ComputerUseOutput.CaptureChanged(null) }
        }
        on<ComputerUseIntent.Public.OwnerReleased>(guard = { state.owner == intent.owner }) {
            goto<ComputerUseState.Ready> { state.asReady() }
            effect { ComputerUseEffect.CloseCapture }
            effect { ComputerUseEffect.PurgeMasters }
            output { ComputerUseOutput.CaptureChanged(null) }
        }
        on<ComputerUseIntent.Internal.FrameCaptured> {
            stay {
                state.copy(
                    master = intent.master,
                    lastPreview = intent.preview,
                    frameCount = state.frameCount + 1,
                )
            }
            output { ComputerUseOutput.FrameReady(intent.preview, intent.tiles) }
        }
        on<ComputerUseIntent.Internal.CropProduced> {
            stay { state.copy(lastCrop = intent.crop) }
            output { ComputerUseOutput.FrameReady(intent.crop) }
        }
        on<ComputerUseIntent.Internal.InputApplied> { output { ComputerUseOutput.InputApplied(intent.action) } }
        on<ComputerUseIntent.Internal.CaptureLost> {
            goto<ComputerUseState.Failed> { ComputerUseState.Failed(intent.reason) }
            effect { ComputerUseEffect.CloseCapture }
            output { ComputerUseOutput.CaptureChanged(null) }
        }
        on<ComputerUseIntent.Internal.Failed> { output { ComputerUseOutput.Rejected(intent.reason) } }
    }
    any {
        on<ComputerUseIntent.Public.Revoke> {
            goto<ComputerUseState.Idle> { ComputerUseState.Idle }
            effect { ComputerUseEffect.CloseCapture }
            effect { ComputerUseEffect.PurgeMasters }
            output { ComputerUseOutput.Revoked }
        }
        on<ComputerUseIntent.Internal.Rejected> { output { ComputerUseOutput.Rejected(intent.reason) } }
    }
    onEffectFailure { effect, _ ->
        when (effect) {
            ComputerUseEffect.ProbeAvailability ->
                ComputerUseIntent.Internal.Failed(ComputerUseFailure.Unavailable)

            ComputerUseEffect.EnumerateWindows -> null

            is ComputerUseEffect.OpenCapture ->
                ComputerUseIntent.Internal.CaptureLost(ComputerUseFailure.CaptureFailed)

            ComputerUseEffect.CloseCapture -> null

            is ComputerUseEffect.CaptureFrame ->
                ComputerUseIntent.Internal.Rejected(ComputerUseFailure.CaptureFailed)

            is ComputerUseEffect.ProduceCrop ->
                ComputerUseIntent.Internal.Rejected(ComputerUseFailure.CaptureFailed)

            is ComputerUseEffect.ApplyInput ->
                ComputerUseIntent.Internal.Rejected(ComputerUseFailure.InputRejected)

            ComputerUseEffect.PurgeMasters -> null
        }
    }
}

/** What remains of a session after it ends: capabilities and the window list, without input and without frames. */
private fun ComputerUseState.Capturing.asReady(): ComputerUseState.Ready = ComputerUseState.Ready(
    capabilities = capabilities,
    targets = targets,
)

/**
 * A crop is served only from a stored master frame of this session. The machine bounds the request against the
 * latest master; an older master of the same session is validated by the host, which reports
 * [ComputerUseFailure.CaptureExpired] once its frame left the cache.
 */
private fun ComputerUseState.Capturing.canCrop(request: CropRequest): Boolean {
    val stored = master ?: return false
    if (request.capture != stored.id) return true
    return request.regionIn(stored) != null
}
