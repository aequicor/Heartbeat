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
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseSuppressionReason
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private val stoppedTurns: ComputerUseStoppedTurns,
) : EffectHandler<ComputerUseEffect, ComputerUseIntent> {
    private val log = Log.tag("ComputerUseEffects")

    override suspend fun handle(effect: ComputerUseEffect, machine: EffectScope<ComputerUseIntent>) {
        when (effect) {
            ComputerUseEffect.ProbeAvailability -> probe(machine)
            ComputerUseEffect.EnumerateWindows -> enumerate(machine)
            is ComputerUseEffect.OpenCapture -> open(effect, machine)
            is ComputerUseEffect.ObserveCapture -> observe(effect, machine)
            is ComputerUseEffect.CloseCapture -> close(effect)
            is ComputerUseEffect.CaptureFrame -> capture(effect, machine)
            is ComputerUseEffect.ProduceCrop -> crop(effect, machine)
            is ComputerUseEffect.ApplyInput -> input(effect, machine)
            is ComputerUseEffect.PurgeMasters -> coordinator.closeSessionAndPurge(effect.session)
            is ComputerUseEffect.StopOwner -> stoppedTurns.stop(effect.owner)
        }
    }

    private suspend fun observe(effect: ComputerUseEffect.ObserveCapture, machine: EffectScope<ComputerUseIntent>) {
        var previous: io.aequicor.heartbeat.feature.computeruse.api.ComputerUseInputActivity? = null
        while (currentCoroutineContext().isActive) {
            val activity = coordinator.observeActivity(effect.session) ?: return
            if (activity != previous) {
                machine.send(ComputerUseIntent.Internal.InputProgress(effect.session, activity))
                previous = activity
            }
            delay(TARGET_POLL_MILLIS)
        }
    }

    private suspend fun close(effect: ComputerUseEffect.CloseCapture) {
        withContext(NonCancellable) {
            coordinator.closeSessionAndPurge(effect.session)
            effect.session?.let { session ->
                // A keyed completion only emits an output; it remains valid after the originating state exited.
                val sent = runningMachine.value.send(ComputerUseIntent.Internal.SessionClosed(session, effect.reason))
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
            log.w { "capture open refused session=${effect.session} reason=$failure" }
            machine.send(ComputerUseIntent.Internal.CaptureLost(failure, effect.session))
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
        log.i {
            "input requested session=${access.active()?.session} action=${effect.action::class.simpleName} " +
                "request=${effect.requestId}"
        }
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
                onProgress = { session, activity ->
                    machine.send(ComputerUseIntent.Internal.InputProgress(session, activity))
                },
            )
        }
        val outcome = when (effect.action) {
            is InputAction.MoveTo, is InputAction.Click, is InputAction.Drag, is InputAction.Scroll -> pointer(apply)
            is InputAction.Type, is InputAction.Key -> apply()
        }
        when (outcome) {
            InputOutcome.Applied -> machine.send(
                ComputerUseIntent.Internal.InputApplied(effect.action, effect.requestId),
            )

            is InputOutcome.Rejected -> reject(machine, outcome.reason, effect.requestId)
        }
    }

    /**
     * The injector's outcome stands: a failed window restore afterwards is logged, so applied input is not reported
     * as rejected and repeated. A cancellation always propagates, even one a restore lease reports.
     */
    private suspend fun pointer(apply: suspend () -> InputOutcome): InputOutcome {
        var finished: InputOutcome? = null
        return try {
            presentation.withoutPresentation(
                ComputerUseSuppressionReason.PointerInput,
            ) { apply().also { finished = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val outcome = finished ?: throw e
            val result = if (outcome is InputOutcome.Rejected) "rejected (${outcome.reason})" else "applied"
            log.w(e) { "pointer input was $result, but app windows were not restored" }
            outcome
        }
    }

    private suspend fun reject(
        machine: EffectScope<ComputerUseIntent>,
        reason: ComputerUseFailure,
        requestId: String?,
    ) {
        log.w { "request refused session=${access.active()?.session} request=$requestId reason=$reason" }
        machine.send(ComputerUseIntent.Internal.Rejected(reason, requestId))
    }

    private companion object {
        const val TARGET_POLL_MILLIS = 100L
    }
}
