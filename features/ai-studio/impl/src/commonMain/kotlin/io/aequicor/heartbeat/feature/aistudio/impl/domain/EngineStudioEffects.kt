package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioDefaults

/** UI effects only wait for profile work; their cancellation never claims native cancellation. */
class EngineStudioEffects(
    private val repository: StudioRepository,
    private val runtime: StudioRuntime,
    private val availability: StudioAvailability,
) : EffectHandler<AiStudioEffect, AiStudioIntent> {
    override suspend fun handle(effect: AiStudioEffect, machine: EffectScope<AiStudioIntent>) {
        when (effect) {
            AiStudioEffect.Load -> machine.send(
                AiStudioIntent.Internal.Loaded(availability.isEnabled(), StudioDefaults(null, runtime.defaults())),
            )

            AiStudioEffect.ObserveAvailability -> availability.observe().collect {
                machine.send(
                    AiStudioIntent.Internal.AvailabilityChanged(it),
                )
            }

            AiStudioEffect.ObserveRuntime -> runtime.state.collect {
                machine.send(
                    AiStudioIntent.Internal.RuntimeChanged(it),
                )
            }

            is AiStudioEffect.CreateSession -> {
                val session = repository.createSession(effect.projectId, titleOf(effect.prompt))
                machine.send(
                    AiStudioIntent.Internal.SessionCreated(effect.paneId, session.id, effect.prompt, effect.settings),
                )
            }

            is AiStudioEffect.Run -> machine.send(
                AiStudioIntent.Internal.RunFinished(
                    effect.sessionId,
                    runtime.run(effect.sessionId, effect.prompt, effect.settings),
                ),
            )

            is AiStudioEffect.Cancel -> runtime.cancel(effect.sessionId)

            is AiStudioEffect.RespondPermission -> runtime.respond(effect.sessionId, effect.requestId, effect.optionId)

            is AiStudioEffect.Apply -> repository.edit(effect.sessionId, effect.edit)
        }
    }
}
