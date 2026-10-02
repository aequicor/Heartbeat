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
 * | Unavailable / Failed | PermissionsRefreshed | capture available | Ready | EnumerateWindows |
 * | Unavailable / Failed | PermissionsRefreshed | capture unavailable | Unavailable | PermissionRequired |
 * | Ready | PermissionsRefreshed | | stay(capabilities, disarmed, stopped owners kept) | |
 * | Ready | RefreshTargets | | stay | EnumerateWindows |
 * | Ready | TargetsLoaded | | stay(targets) | |
 * | Ready | ArmInput | input is available and no authorization binding | stay(armed) | |
 * | Ready | BeginCapture | the mode is supported, the owner is not stopped | Capturing | OpenCapture, CaptureChanged |
 * | Ready | OwnerReleased | owner was stopped | stay(stopped-owner) | |
 * | Capturing | CaptureOpened | matching session | stay(open) | ObserveCapture |
 * | Capturing | InputProgress | matching session, newer revision | stay(activity) | |
 * | Capturing | ArmInput | input available, matching binding and owner | stay(armed) | |
 * | Capturing | Capture | host open, matching session and owner | stay | CaptureFrame |
 * | Capturing | Crop | host open, region in master, session and owner match | stay | ProduceCrop |
 * | Capturing | Input | host open, armed, mode allows input, binding and owner match | stay | ApplyInput |
 * | Capturing | FrameCaptured | | stay(master, preview, frames+1) | FrameReady |
 * | Capturing | CropProduced | | stay(lastCrop) | FrameReady |
 * | Capturing | InputApplied | | stay | InputApplied output |
 * | Capturing | SwitchMode | supported mode, same owner and session | Capturing (re-entered) | CloseCapture(old), |
 * | | | | | OpenCapture, CaptureChanged |
 * | Capturing | EndCapture | | Ready(disarmed) | CloseCapture, PurgeMasters, CaptureChanged(null) |
 * | Capturing | OwnerReleased | same owner | Ready(disarmed) | CloseCapture, PurgeMasters, CaptureChanged(null) |
 * | Capturing | OwnerReleased | another owner that was stopped | stay(stopped-owner) | |
 * | Capturing | CaptureLost | | Failed | CloseCapture, PurgeMasters, CaptureChanged(null) |
 * | Capturing | Rejected / Failed | | stay | Rejected output |
 * | Capturing | RefreshTargets / TargetsLoaded | | stay(targets) | EnumerateWindows |
 * | Capturing | CancelSession | matching session and owner | Idle | keyed cleanup, Revoked |
 * | Capturing | StopAgent | same owner | Ready(disarmed, stopped+owner) | StopOwner, CloseCapture, PurgeMasters, |
 * | | | | | CaptureChanged(null) |
 * | any | SessionClosed | | stay | SessionClosed output |
 * | any | Revoke | | Idle | CloseCapture, PurgeMasters, Revoked |
 *
 * Stopped turns are fenced here only while the machine stays in Ready/Capturing (`stoppedOwners`); leaving them
 * resets the set, and the impl's tool layer, filled by the StopOwner effect, refuses those turns until they end.
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
        on<ComputerUseIntent.Internal.PermissionsRefreshed> {
            stay { state.copy(capabilities = intent.capabilities, isInputArmed = false) }
        }
        on<ComputerUseIntent.Public.Retry> {
            goto<ComputerUseState.Checking> { ComputerUseState.Checking }
            effect { ComputerUseEffect.ProbeAvailability }
        }
        on<ComputerUseIntent.Public.RefreshTargets> { effect { ComputerUseEffect.EnumerateWindows } }
        on<ComputerUseIntent.Internal.TargetsLoaded> { stay { state.copy(targets = intent.targets) } }
        on<ComputerUseIntent.Public.ArmInput>(guard = {
            state.capabilities.isInputAvailable && intent.expectedSession == null && intent.expectedCapture == null
        }) {
            stay { state.copy(isInputArmed = intent.isArmed) }
        }
        on<ComputerUseIntent.Public.BeginCapture>(guard = {
            state.capabilities.supports(intent.mode) && !state.stoppedOwners.isStopped(intent.owner)
        }) {
            goto<ComputerUseState.Capturing> {
                ComputerUseState.Capturing(
                    session = intent.session,
                    mode = intent.mode,
                    owner = intent.owner,
                    capabilities = state.capabilities,
                    targets = state.targets,
                    stoppedOwners = state.stoppedOwners,
                )
            }
            effect { ComputerUseEffect.OpenCapture(intent.mode, intent.session) }
            output { ComputerUseOutput.CaptureChanged(intent.mode) }
        }
        on<ComputerUseIntent.Public.OwnerReleased>(guard = { state.stoppedOwners.isStopped(intent.owner) }) {
            stay { state.copy(stoppedOwners = state.stoppedOwners.without(intent.owner)) }
        }
    }
    state<ComputerUseState.Capturing> {
        on<ComputerUseIntent.Internal.CaptureOpened>(guard = { state.session == intent.session }) {
            stay { state.copy(isOpen = true) }
            effect { ComputerUseEffect.ObserveCapture(state.session) }
        }
        on<ComputerUseIntent.Internal.InputProgress>(guard = {
            state.session == intent.session && state.isOpen && intent.activity.revision >= state.inputActivity.revision
        }) {
            stay { state.copy(inputActivity = intent.activity) }
        }
        on<ComputerUseIntent.Public.RefreshTargets> { effect { ComputerUseEffect.EnumerateWindows } }
        on<ComputerUseIntent.Internal.TargetsLoaded> { stay { state.copy(targets = intent.targets) } }
        on<ComputerUseIntent.Public.ArmInput>(guard = {
            state.capabilities.isInputAvailable && state.isOwnedBy(intent.expectedOwner) &&
                state.matchesBinding(intent.expectedSession, intent.expectedCapture)
        }) {
            stay { state.copy(isInputArmed = intent.isArmed) }
        }
        on<ComputerUseIntent.Public.Capture>(guard = {
            state.isOpen && state.isOwnedBy(intent.expectedOwner) &&
                (intent.expectedSession == null || intent.expectedSession == state.session)
        }) {
            effect { ComputerUseEffect.CaptureFrame(intent.request, intent.requestId) }
        }
        on<ComputerUseIntent.Public.Crop>(guard = {
            state.isOpen && state.canCrop(intent.request) && state.isOwnedBy(intent.expectedOwner) &&
                (intent.expectedSession == null || intent.expectedSession == state.session)
        }) {
            effect { ComputerUseEffect.ProduceCrop(intent.request, intent.requestId) }
        }
        on<ComputerUseIntent.Public.Input>(
            guard = {
                state.isOpen && state.isInputArmed && state.capabilities.allowsInput(state.mode) &&
                    state.isOwnedBy(intent.expectedOwner) &&
                    state.matchesBinding(intent.expectedSession, intent.expectedCapture)
            },
        ) { effect { ComputerUseEffect.ApplyInput(intent.action, intent.requestId, state.lastPreview?.id) } }
        on<ComputerUseIntent.Public.SwitchMode>(guard = {
            state.capabilities.supports(intent.mode) && state.isOwnedBy(intent.owner) &&
                (intent.expectedSession == null || intent.expectedSession == state.session)
        }) {
            goto<ComputerUseState.Capturing> {
                ComputerUseState.Capturing(
                    session = intent.session,
                    mode = intent.mode,
                    owner = state.owner,
                    capabilities = state.capabilities,
                    targets = state.targets,
                    isInputArmed = false,
                    stoppedOwners = state.stoppedOwners,
                )
            }
            effect { ComputerUseEffect.CloseCapture(state.session) }
            effect { ComputerUseEffect.OpenCapture(intent.mode, intent.session) }
            output { ComputerUseOutput.CaptureChanged(intent.mode) }
        }
        on<ComputerUseIntent.Public.CancelSession>(guard = {
            state.session == intent.session && state.isOwnedBy(intent.expectedOwner)
        }) {
            goto<ComputerUseState.Idle> { ComputerUseState.Idle }
            effect { ComputerUseEffect.CloseCapture(state.session) }
            effect { ComputerUseEffect.PurgeMasters(state.session) }
            output { ComputerUseOutput.Revoked }
        }
        on<ComputerUseIntent.Public.StopAgent>(guard = { state.owner == intent.owner }) {
            goto<ComputerUseState.Ready> {
                state.asReady().copy(stoppedOwners = state.stoppedOwners + intent.owner)
            }
            effect { ComputerUseEffect.StopOwner(intent.owner) }
            effect { ComputerUseEffect.CloseCapture(state.session) }
            effect { ComputerUseEffect.PurgeMasters(state.session) }
            output { ComputerUseOutput.CaptureChanged(null) }
        }
        on<ComputerUseIntent.Public.EndCapture> {
            goto<ComputerUseState.Ready> { state.asReady() }
            effect { ComputerUseEffect.CloseCapture(state.session) }
            effect { ComputerUseEffect.PurgeMasters(state.session) }
            output { ComputerUseOutput.CaptureChanged(null) }
        }
        on<ComputerUseIntent.Public.OwnerReleased>(guard = { state.owner == intent.owner }) {
            goto<ComputerUseState.Ready> { state.asReady() }
            effect { ComputerUseEffect.CloseCapture(state.session) }
            effect { ComputerUseEffect.PurgeMasters(state.session) }
            output { ComputerUseOutput.CaptureChanged(null) }
        }
        on<ComputerUseIntent.Public.OwnerReleased>(guard = {
            state.owner != intent.owner && state.stoppedOwners.isStopped(intent.owner)
        }) {
            stay { state.copy(stoppedOwners = state.stoppedOwners.without(intent.owner)) }
        }
        on<ComputerUseIntent.Internal.FrameCaptured> {
            stay {
                state.copy(
                    master = intent.master,
                    lastPreview = intent.preview,
                    frameCount = state.frameCount + 1,
                )
            }
            output { ComputerUseOutput.FrameReady(intent.preview, intent.tiles, intent.requestId, intent.master) }
        }
        on<ComputerUseIntent.Internal.CropProduced> {
            stay { state.copy(lastCrop = intent.crop, lastPreview = intent.crop) }
            output { ComputerUseOutput.FrameReady(intent.crop, requestId = intent.requestId, master = intent.master) }
        }
        on<ComputerUseIntent.Internal.InputApplied> {
            output {
                ComputerUseOutput.InputApplied(
                    intent.action,
                    intent.requestId,
                )
            }
        }
        on<ComputerUseIntent.Internal.CaptureLost>(guard = {
            intent.session == null || state.session == intent.session
        }) {
            goto<ComputerUseState.Failed> { ComputerUseState.Failed(intent.reason, state.session) }
            effect { ComputerUseEffect.CloseCapture(state.session, intent.reason) }
            effect { ComputerUseEffect.PurgeMasters(state.session) }
            output { ComputerUseOutput.CaptureChanged(null) }
        }
        on<ComputerUseIntent.Internal.Failed> { output { ComputerUseOutput.Rejected(intent.reason) } }
    }
    any {
        on<ComputerUseIntent.Internal.PermissionsRefreshed>(guard = {
            (state is ComputerUseState.Unavailable || state is ComputerUseState.Failed) &&
                intent.capabilities.isCaptureAvailable
        }) {
            goto<ComputerUseState.Ready> { ComputerUseState.Ready(intent.capabilities) }
            effect { ComputerUseEffect.EnumerateWindows }
        }
        on<ComputerUseIntent.Internal.PermissionsRefreshed>(guard = {
            (state is ComputerUseState.Unavailable || state is ComputerUseState.Failed) &&
                !intent.capabilities.isCaptureAvailable
        }) {
            goto<ComputerUseState.Unavailable> { ComputerUseState.Unavailable(intent.capabilities.blockers) }
            output { ComputerUseOutput.PermissionRequired(intent.capabilities.blockers) }
        }
        on<ComputerUseIntent.Internal.SessionClosed> {
            output { ComputerUseOutput.SessionClosed(intent.session, intent.reason) }
        }
        on<ComputerUseIntent.Public.Revoke> {
            goto<ComputerUseState.Idle> { ComputerUseState.Idle }
            effect { ComputerUseEffect.CloseCapture((state as? ComputerUseState.Capturing)?.session) }
            effect { ComputerUseEffect.PurgeMasters((state as? ComputerUseState.Capturing)?.session) }
            output { ComputerUseOutput.Revoked }
        }
        on<ComputerUseIntent.Internal.Rejected> {
            output { ComputerUseOutput.Rejected(intent.reason, intent.requestId) }
        }
    }
    onEffectFailure { effect, _ ->
        when (effect) {
            ComputerUseEffect.ProbeAvailability ->
                ComputerUseIntent.Internal.Failed(ComputerUseFailure.Unavailable)

            ComputerUseEffect.EnumerateWindows -> null

            is ComputerUseEffect.OpenCapture ->
                ComputerUseIntent.Internal.CaptureLost(ComputerUseFailure.CaptureFailed, effect.session)

            is ComputerUseEffect.CloseCapture -> null

            is ComputerUseEffect.ObserveCapture -> null

            is ComputerUseEffect.CaptureFrame ->
                ComputerUseIntent.Internal.Rejected(ComputerUseFailure.CaptureFailed, effect.requestId)

            is ComputerUseEffect.ProduceCrop ->
                ComputerUseIntent.Internal.Rejected(ComputerUseFailure.CaptureFailed, effect.requestId)

            is ComputerUseEffect.ApplyInput ->
                ComputerUseIntent.Internal.Rejected(ComputerUseFailure.InputRejected, effect.requestId)

            is ComputerUseEffect.PurgeMasters -> null

            is ComputerUseEffect.StopOwner -> null
        }
    }
}

/** What remains of a session after it ends: capabilities and the window list, without input and without frames. */
private fun ComputerUseState.Capturing.asReady(): ComputerUseState.Ready = ComputerUseState.Ready(
    capabilities = capabilities,
    targets = targets,
    stoppedOwners = stoppedOwners,
)

/** A named owner must own the session; an operation without one is checked by its session binding only. */
private fun ComputerUseState.Capturing.isOwnedBy(expected: CaptureOwner?): Boolean =
    expected == null || expected == owner

private fun Set<CaptureOwner.Agent>.isStopped(owner: CaptureOwner): Boolean = any { it == owner }

private fun Set<CaptureOwner.Agent>.without(owner: CaptureOwner): Set<CaptureOwner.Agent> = filterTo(mutableSetOf()) {
    it != owner
}

/**
 * An authorization binding names the approved session and frame. Either part alone is checked too, so a frame
 * named without its session cannot arm or drive another capture.
 */
private fun ComputerUseState.Capturing.matchesBinding(session: CaptureSessionId?, capture: CaptureId?): Boolean {
    if (session == null && capture == null) return true
    return (session == null || session == this.session) && capture == lastPreview?.id
}

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
