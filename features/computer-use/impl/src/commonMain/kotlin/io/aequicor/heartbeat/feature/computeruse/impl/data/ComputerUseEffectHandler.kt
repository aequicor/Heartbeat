package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEffect
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs machine IO only after revalidating the live feature toggles and operating system permissions. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ComputerUseEffectHandler(
    private val access: ComputerUseAccess,
    private val coordinator: CaptureCoordinator,
    private val captures: ComputerUseCaptureExecutor,
    private val runningMachine: Lazy<Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>>,
    private val presentation: ComputerUseCapturePresentation,
) : EffectHandler<ComputerUseEffect, ComputerUseIntent> {
    private val log = Log.tag("ComputerUseEffects")

    override suspend fun handle(effect: ComputerUseEffect, machine: EffectScope<ComputerUseIntent>) {
        when (effect) {
            ComputerUseEffect.ProbeAvailability -> probe(machine)
            ComputerUseEffect.EnumerateWindows -> enumerate(machine)
            is ComputerUseEffect.OpenCapture -> open(effect, machine)
            is ComputerUseEffect.CloseCapture -> close(effect)
            is ComputerUseEffect.CaptureFrame -> capture(effect, machine)
            is ComputerUseEffect.ProduceCrop -> crop(effect, machine)
            is ComputerUseEffect.ApplyInput -> input(effect, machine)
            is ComputerUseEffect.PurgeMasters -> coordinator.closeSessionAndPurge(effect.session)
        }
    }

    private suspend fun close(effect: ComputerUseEffect.CloseCapture) {
        withContext(NonCancellable) {
            coordinator.closeSessionAndPurge(effect.session)
            effect.session?.let { session ->
                // A keyed completion only emits an output; it remains valid after the originating state exited.
                val sent = runningMachine.value.send(ComputerUseIntent.Internal.SessionClosed(session))
                log.d { "capture cleanup acknowledged session=$session result=$sent" }
            }
        }
    }

    private suspend fun probe(machine: EffectScope<ComputerUseIntent>) {
        val capabilities = access.probe()
        log.i {
            "probed capture=${capabilities.isCaptureAvailable} window=${capabilities.isWindowCaptureAvailable} " +
                "input=${capabilities.isInputAvailable} blockers=${capabilities.blockers.size}"
        }
        if (capabilities.isCaptureAvailable) {
            machine.send(ComputerUseIntent.Internal.Available(capabilities))
        } else {
            machine.send(ComputerUseIntent.Internal.Blocked(capabilities.blockers))
        }
    }

    private suspend fun enumerate(machine: EffectScope<ComputerUseIntent>) {
        val targets = if (access.probe().isWindowCaptureAvailable) coordinator.targets() else emptyList()
        log.d { "window list size=${targets.size}" }
        machine.send(ComputerUseIntent.Internal.TargetsLoaded(targets))
    }

    private suspend fun open(effect: ComputerUseEffect.OpenCapture, machine: EffectScope<ComputerUseIntent>) {
        val failure = access.captureFailure(isOpenRequired = false)
            ?: coordinator.open(effect.session, effect.mode)
        if (failure != null) {
            log.w { "capture open refused reason=$failure" }
            machine.send(ComputerUseIntent.Internal.CaptureLost(failure))
        } else {
            machine.send(ComputerUseIntent.Internal.CaptureOpened(effect.session))
        }
    }

    private suspend fun capture(effect: ComputerUseEffect.CaptureFrame, machine: EffectScope<ComputerUseIntent>) {
        val outcome = captures.capture(effect.request)
        val reference = outcome.reference
        if (reference == null) {
            reject(machine, outcome.failure ?: ComputerUseFailure.CaptureFailed, effect.requestId)
        } else {
            machine.send(
                ComputerUseIntent.Internal.FrameCaptured(
                    master = outcome.master ?: reference,
                    preview = reference,
                    tiles = outcome.tiles,
                    requestId = effect.requestId,
                ),
            )
        }
    }

    private suspend fun crop(effect: ComputerUseEffect.ProduceCrop, machine: EffectScope<ComputerUseIntent>) {
        val failure = access.captureFailure()
        if (failure != null) {
            reject(machine, failure, effect.requestId)
            return
        }
        when (val outcome = coordinator.crop(effect.request)) {
            is CaptureOutcome.Produced -> {
                val reference = outcome.result.reference
                if (reference == null) {
                    reject(machine, ComputerUseFailure.CaptureFailed, effect.requestId)
                } else {
                    machine.send(
                        ComputerUseIntent.Internal.CropProduced(reference, effect.requestId, outcome.result.master),
                    )
                }
            }

            is CaptureOutcome.Rejected -> reject(machine, outcome.reason, effect.requestId)
        }
    }

    private suspend fun input(effect: ComputerUseEffect.ApplyInput, machine: EffectScope<ComputerUseIntent>) {
        val failure = access.inputFailure()
        if (failure != null) {
            reject(machine, failure, effect.requestId)
            return
        }
        val apply: suspend () -> InputOutcome = {
            coordinator.input(
                effect.action,
                effect.expectedCapture,
                isFrameBound = true,
                guard = access::inputFailure,
            )
        }
        val outcome = when (effect.action) {
            is InputAction.MoveTo, is InputAction.Click, is InputAction.Drag, is InputAction.Scroll -> {
                presentation.withoutPresentation(apply)
            }

            is InputAction.Type, is InputAction.Key -> apply()
        }
        when (outcome) {
            InputOutcome.Applied -> machine.send(
                ComputerUseIntent.Internal.InputApplied(effect.action, effect.requestId),
            )

            is InputOutcome.Rejected -> reject(machine, outcome.reason, effect.requestId)
        }
    }

    private suspend fun reject(
        machine: EffectScope<ComputerUseIntent>,
        reason: ComputerUseFailure,
        requestId: String?,
    ) {
        log.w { "request refused reason=$reason" }
        machine.send(ComputerUseIntent.Internal.Rejected(reason, requestId))
    }
}
