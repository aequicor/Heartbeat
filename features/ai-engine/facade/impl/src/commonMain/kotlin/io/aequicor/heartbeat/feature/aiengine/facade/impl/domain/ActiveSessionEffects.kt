package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Executes machine effects against the adapter's native handle. Every native command is handed off to the
 * profile-owned [commands] scope and only awaited here: leaving a state cancels the effect, never an accepted
 * native operation. Acknowledgements are sent only after the handoff returned.
 */
class ActiveSessionEffects(private val native: ActiveSession, commands: CoroutineScope) :
    EffectHandler<ActiveSessionEffect, ActiveSessionIntent> {
    private val log = Log.tag("ActiveSessionEffects")

    /** A failed command is reported to its awaiting effect only, never to the profile scope. */
    private val profileJob = commands.coroutineContext[Job]
    private val handOffJob = SupervisorJob(profileJob)
    private val handOffs = CoroutineScope(commands.coroutineContext + handOffJob)
    private val recheckSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Local/native turn id mapping of this handle. */
    val correlation: TurnCorrelation = TurnCorrelation()

    /** Emits after every reconciliation attempt, successful or not. */
    val rechecks: Flow<Unit> = recheckSignals

    override suspend fun handle(effect: ActiveSessionEffect, machine: EffectScope<ActiveSessionIntent>) {
        when (effect) {
            is ActiveSessionEffect.Submit -> {
                log.i { "submit request=${effect.request.id.value} turn=${effect.turn.id.value}" }
                handOff("send") {
                    val nativeTurn = command(SendsPrompts).send(effect.request)
                    correlation.bind(nativeTurn, effect.turn.id)
                }
                machine.send(ActiveSessionIntent.Internal.Accepted(effect.turn.id))
            }

            is ActiveSessionEffect.Cancel -> {
                log.i { "cancel turn=${effect.turn.value}" }
                handOff("cancel") { command(CancelsTurns).cancel(correlation.native(effect.turn)) }
            }

            is ActiveSessionEffect.Decide -> {
                log.i { "decide turn=${effect.decision.turn.value} request=${effect.decision.request.value}" }
                handOff("respond") { command(RequestsPermissions).respond(correlation.native(effect.decision)) }
            }

            is ActiveSessionEffect.Recheck -> try {
                machine.send(recheck(effect.turn))
            } finally {
                log.d { "recheck attempt finished" }
                recheckSignals.tryEmit(Unit)
            }

            ActiveSessionEffect.Release -> {
                log.i { "release native handle" }
                handOff("release") { native.close() }
                machine.send(ActiveSessionIntent.Internal.Released)
                handOffJob.complete()
            }
        }
    }

    /** Authoritative native state; never resubmits and never assumes a remembered turn stopped. */
    private suspend fun recheck(turn: TurnId?): ActiveSessionIntent.Internal.Synchronized {
        log.i { "recheck turn=${turn?.value ?: "none"}" }
        val reconciler = native.features.resolve(ReconcilesSession)
        if (reconciler is FeatureAccess.Available) handOff("synchronize") { reconciler.feature.synchronize() }
        val state = correlation.localize(native.state.value)
        if (state is ActiveSessionState.Unavailable) fail(state.failure)
        if (!state.isReachable()) fail(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
        val active = state.activeTurn()
        val pending = state.pending()
            .filter { active != null && it.turn == active.id && it.id !in active.resolvedPermissions }
            .distinctBy { it.id }
        val finished = state.lastTurn()?.takeIf { it.id == turn && it.id != active?.id }
        val completed = finished?.let { ActiveSessionIntent.Internal.Finished(it.id, checkNotNull(it.outcome)) }
        return ActiveSessionIntent.Internal.Synchronized(active, pending, completed)
    }

    private fun <F : EngineFeature> command(key: EngineFeatureKey<F>): F = native.features.resolve(key).orFail()

    /**
     * Runs a native command in the profile scope. The failure is logged here, because nobody awaits the command
     * once its effect was cancelled; the awaiting effect rethrows it to onEffectFailure.
     *
     * Once the handoff scope ended (handle released or profile closed) the command is refused as a domain
     * failure: the cancellation of that foreign scope must not escape from a still-running effect.
     */
    private suspend fun <T> handOff(operation: String, block: suspend () -> T): T {
        if (!handOffJob.isActive) fail(closedFailure(operation))
        val command = handOffs.async { logged(operation, block) }
        // join throws only on our own cancellation (state left); a command the ended handoff scope did not let
        // complete is translated (logged already recorded a native failure), anything else is rethrown by await.
        command.join()
        if (command.isCancelled && !handOffJob.isActive) {
            log.w { "native $operation dropped, handoff scope ended" }
            fail(closedFailure(operation))
        }
        return command.await()
    }

    private suspend fun <T> logged(operation: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        log.w(e) { "native $operation failed failure=${e.failure.code}" }
        throw e
    } catch (e: Exception) {
        log.e(e) { "native $operation crashed" }
        throw e
    }

    private fun closedFailure(operation: String): EngineFailure {
        val isProfileClosed = profileJob?.isActive == false
        log.w { "native $operation refused, profileClosed=$isProfileClosed" }
        return EngineFailure.Lifecycle(
            if (isProfileClosed) LifecycleFailureReason.ProfileClosed else LifecycleFailureReason.SessionClosed,
        )
    }
}
