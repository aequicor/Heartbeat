package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEffect
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.impl.domain.OsPermissions

/**
 * Runs the IO of the computer use machine.
 *
 * Every refusal comes back as an intent, so the machine — not this handler — decides what a failure means for
 * the session. Only sizes, identifiers and failure codes are logged: window titles, frame paths and pixel data
 * never reach a log record.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ComputerUseEffectHandler(
    private val permissions: OsPermissions,
    private val coordinator: CaptureCoordinator,
) : EffectHandler<ComputerUseEffect, ComputerUseIntent> {
    private val log = Log.tag("ComputerUseEffects")

    override suspend fun handle(effect: ComputerUseEffect, machine: EffectScope<ComputerUseIntent>) {
        when (effect) {
            ComputerUseEffect.ProbeAvailability -> probe(machine)
            ComputerUseEffect.EnumerateWindows -> enumerate(machine)
            is ComputerUseEffect.OpenCapture -> open(effect, machine)
            ComputerUseEffect.CloseCapture -> coordinator.close()
            is ComputerUseEffect.CaptureFrame -> capture(effect, machine)
            is ComputerUseEffect.ProduceCrop -> crop(effect, machine)
            is ComputerUseEffect.ApplyInput -> input(effect, machine)
            ComputerUseEffect.PurgeMasters -> coordinator.purge()
        }
    }

    private suspend fun probe(machine: EffectScope<ComputerUseIntent>) {
        val capabilities = permissions.probe()
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
        val targets = coordinator.targets()
        log.d { "window list size=${targets.size}" }
        machine.send(ComputerUseIntent.Internal.TargetsLoaded(targets))
    }

    private suspend fun open(effect: ComputerUseEffect.OpenCapture, machine: EffectScope<ComputerUseIntent>) {
        val failure = coordinator.open(effect.session, effect.mode)
        if (failure != null) {
            log.w { "capture open refused reason=$failure" }
            machine.send(ComputerUseIntent.Internal.CaptureLost(failure))
        }
    }

    private suspend fun capture(effect: ComputerUseEffect.CaptureFrame, machine: EffectScope<ComputerUseIntent>) {
        when (val outcome = coordinator.capture(effect.request)) {
            is CaptureOutcome.Produced -> {
                val reference = outcome.result.reference
                if (reference == null) {
                    reject(machine, ComputerUseFailure.CaptureFailed)
                } else {
                    machine.send(
                        ComputerUseIntent.Internal.FrameCaptured(
                            master = outcome.result.master ?: reference,
                            preview = reference,
                            tiles = outcome.result.tiles,
                        ),
                    )
                }
            }

            is CaptureOutcome.Rejected -> reject(machine, outcome.reason)
        }
    }

    private suspend fun crop(effect: ComputerUseEffect.ProduceCrop, machine: EffectScope<ComputerUseIntent>) {
        when (val outcome = coordinator.crop(effect.request)) {
            is CaptureOutcome.Produced -> {
                val reference = outcome.result.reference
                if (reference == null) {
                    reject(machine, ComputerUseFailure.CaptureFailed)
                } else {
                    machine.send(ComputerUseIntent.Internal.CropProduced(reference))
                }
            }

            is CaptureOutcome.Rejected -> reject(machine, outcome.reason)
        }
    }

    private suspend fun input(effect: ComputerUseEffect.ApplyInput, machine: EffectScope<ComputerUseIntent>) {
        when (val outcome = coordinator.input(effect.action)) {
            InputOutcome.Applied -> machine.send(ComputerUseIntent.Internal.InputApplied(effect.action))
            is InputOutcome.Rejected -> reject(machine, outcome.reason)
        }
    }

    private suspend fun reject(machine: EffectScope<ComputerUseIntent>, reason: ComputerUseFailure) {
        log.w { "request refused reason=$reason" }
        machine.send(ComputerUseIntent.Internal.Rejected(reason))
    }
}
