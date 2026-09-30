package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Joins the user's selected routes with cached capability updates; observation never starts discovery. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun EngineFacade.observeStudioModels(
    selections: ModelSelections,
    sources: AuthSources,
): Flow<List<StudioModel>> =
    combine(selections.observe(), bindings.state, engines.state, sources.state) { selected, bindings, engines, auth ->
        selected.bindings.mapNotNull { enabled ->
            val binding = bindings.firstOrNull { it.id == enabled.binding && it.isEnabled } ?: return@mapNotNull null
            val engine = engines.firstOrNull { it.descriptor.id == binding.engine } ?: return@mapNotNull null
            val label = auth.firstOrNull { it.info.id == binding.authSource }?.info?.label.orEmpty()
            models.observe(binding.engine, binding.id).map { snapshot ->
                enabled.models.map { model ->
                    val target = EngineTarget(binding.engine, binding.id, model)
                    studioModel(
                        target,
                        snapshot.models.firstOrNull { it.target == target },
                        engine.descriptor.title,
                        label,
                        engine.descriptor.isLocalWorkspaceSupported,
                        isTrustSupported = AppliesTrustLevels.id in engine.descriptor.declaredFeatures,
                        isModelSwitchSupported = engine.descriptor.declaredFeatures.supportsStudioModelSwitch(),
                    )
                }
            }
        }
    }.flatMapLatest { observations ->
        if (observations.isEmpty()) flowOf(emptyList()) else combine(observations) { it.flatMap { models -> models } }
    }

/** Both native configuration changes and the legacy model setter can keep a conversation on its binding. */
internal fun Set<EngineFeatureId>.supportsStudioModelSwitch(): Boolean =
    ChangesSessionConfiguration.id in this || SwitchesModels.id in this

/** Compact model identity and full connection context come from data, never from UI string parsing. */
internal fun studioModel(
    target: EngineTarget,
    info: ModelInfo?,
    engineName: String,
    connectionName: String,
    isLocalProjectSupported: Boolean,
    isTrustSupported: Boolean = false,
    isModelSwitchSupported: Boolean = false,
): StudioModel {
    val shortName = info?.title?.takeIf(String::isNotBlank) ?: target.model.value
    return StudioModel(
        id = target.studioModelId(),
        name = listOf(engineName, shortName, connectionName).filter(String::isNotBlank).joinToString(" · "),
        isResearchSupported = target.engine == KoogEngineId,
        isLocalProjectSupported = isLocalProjectSupported,
        shortName = shortName,
        reasoningEfforts = info?.reasoningEfforts.orEmpty(),
        defaultReasoningEffort = info?.defaultReasoningEffort,
        isTrustSupported = isTrustSupported,
        isModelSwitchSupported = isModelSwitchSupported,
    )
}

/** Whether the engine of [target] applies trust levels; false when the route is not offered. */
internal fun List<StudioModel>.isTrustSupported(target: EngineTarget): Boolean {
    val id = target.studioModelId()
    return firstOrNull { it.id == id }?.isTrustSupported == true
}

/** Native effort levels the catalog advertises for [target]; empty when the route is not offered. */
internal fun List<StudioModel>.reasoningEfforts(target: EngineTarget): List<String> {
    val id = target.studioModelId()
    return firstOrNull { it.id == id }?.reasoningEfforts.orEmpty()
}
