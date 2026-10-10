package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Profile-owned command dispatch. Late completion only releases the command that captured its submission. */
internal class PiSessionCommands(
    private val environment: PiSessionEnvironment,
    private val submissions: PiSubmissions,
    private val execute: suspend (ActiveSessionEffect, PiSubmission?) -> Unit,
    private val failed: suspend (ActiveSessionEffect, PiSubmission?, EngineFailure, Boolean) -> Unit,
) {
    private val log = Log.tag("PiSessionCommands")
    private var pending: ActiveSessionEffect? = null
    private var isStarted = false
    var isPending = false
        private set

    fun prepare(effect: ActiveSessionEffect) {
        pending = effect
        isStarted = false
        isPending = true
    }

    fun clear() {
        pending = null
        isPending = false
    }

    fun handoff(effect: ActiveSessionEffect) {
        if (pending == effect && !isStarted) {
            val entry = submissions.pending?.takeIf {
                effect is ActiveSessionEffect.Submit && it.turn.id == effect.turn.id
            }
            if (effect is ActiveSessionEffect.Submit && entry == null) return
            isStarted = true
            environment.profile.coroutineScope.launch(environment.dispatchers.main) { run(effect, entry) }
        }
    }

    private suspend fun run(effect: ActiveSessionEffect, entry: PiSubmission?) {
        try {
            execute(effect, entry)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PromptNotSentException) {
            log.w(error) { "Pi prompt was rejected before delivery" }
            failed(effect, entry, error.failure, false)
        } catch (error: EngineException) {
            log.w(error) { "Pi native command failed" }
            failed(effect, entry, error.failure, true)
        } catch (error: Exception) {
            log.w(error.withoutDetails()) { "Pi native command failed" }
            failed(effect, entry, EngineFailure.Unknown(), true)
        } finally {
            if (pending == effect) isPending = false
            entry?.let(submissions::finish)
        }
    }
}

/** Exact command identity; commands without their own turn are scoped to the captured current turn. */
internal fun ActiveSessionEffect.piAffectedTurn(current: TurnId?): TurnId? = when (this) {
    is ActiveSessionEffect.Submit -> turn.id
    is ActiveSessionEffect.Cancel -> turn
    is ActiveSessionEffect.Recheck, ActiveSessionEffect.Release, is ActiveSessionEffect.Decide -> current
}
