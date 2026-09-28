package io.aequicor.heartbeat.feature.researchchat.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatEffect
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatEnabled
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatIntent
import io.aequicor.heartbeat.feature.researchchat.api.ResearchWorkspace
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Screen effects observe and await work whose execution lifetime belongs to the active profile. */
@Inject
internal class ResearchChatEffects(private val repository: ResearchRepository, private val toggles: FeatureToggles) :
    EffectHandler<ResearchChatEffect, ResearchChatIntent> {
    override suspend fun handle(effect: ResearchChatEffect, machine: EffectScope<ResearchChatIntent>) {
        when (effect) {
            is ResearchChatEffect.Load -> load(effect, machine)

            ResearchChatEffect.Observe -> combine(
                repository.observe(),
                toggles.observe(ResearchChatEnabled),
                toggles.observe(KoogEngineEnabled),
            ) { workspace, research, koog ->
                ResearchChatIntent.Internal.Observed(workspace, research && koog)
            }.collect { machine.send(it) }

            is ResearchChatEffect.CreateSession -> {
                val session = repository.createSession(effect.target)
                machine.send(
                    ResearchChatIntent.Internal.Created(
                        repository.observe().first(),
                        session.id,
                        session.questions.first().id,
                    ),
                )
            }

            is ResearchChatEffect.CreateQuestion -> {
                val questionId = repository.createQuestion(effect.sessionId)
                machine.send(
                    ResearchChatIntent.Internal.Created(repository.observe().first(), effect.sessionId, questionId),
                )
            }

            is ResearchChatEffect.AddResource -> {
                repository.addResource(effect.sessionId, effect.questionId, effect.input)
                machine.send(ResearchChatIntent.Internal.ResourceAdded)
            }

            is ResearchChatEffect.SelectResource -> {
                repository.setResourceSelected(
                    effect.sessionId,
                    effect.questionId,
                    effect.resourceId,
                    effect.isSelected,
                )
                machine.send(ResearchChatIntent.Internal.Mutated)
            }

            is ResearchChatEffect.ShareResource -> {
                repository.shareResource(effect.sessionId, effect.resourceId)
                machine.send(ResearchChatIntent.Internal.Mutated)
            }

            is ResearchChatEffect.RemoveResource -> {
                repository.removeResource(effect.sessionId, effect.resourceId)
                machine.send(ResearchChatIntent.Internal.Mutated)
            }

            is ResearchChatEffect.Run -> {
                val run = repository.run(effect.sessionId, effect.questionId, effect.prompt)
                if (run.accepted.await()) machine.send(ResearchChatIntent.Internal.Submitted(effect.questionId))
                machine.send(ResearchChatIntent.Internal.RunFinished(effect.questionId, !run.completion.await()))
            }

            is ResearchChatEffect.Stop -> repository.stop(effect.questionId)
        }
    }

    private suspend fun load(effect: ResearchChatEffect.Load, machine: EffectScope<ResearchChatIntent>) {
        val isEnabled = effect.target.engine == KoogEngineId && toggles.get(ResearchChatEnabled) &&
            toggles.get(KoogEngineEnabled)
        val workspace = if (isEnabled) repository.prepare(effect.target) else ResearchWorkspace()
        machine.send(ResearchChatIntent.Internal.Loaded(workspace, isEnabled))
    }
}
