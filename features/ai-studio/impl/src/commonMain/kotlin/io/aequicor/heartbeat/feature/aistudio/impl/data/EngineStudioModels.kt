package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.BindingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExactRouteReadiness
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Joins selected routes with loaded cached capabilities without starting discovery. Only a route confirmed by
 * [ExactRouteReadiness] is offered: missing or changed bindings, sources and runtime closures hide it, and
 * [RouteAvailability.Unknown] is never treated as available. Confirmation belongs to one cancellable owner
 * refresh; reading this flow performs no IO. The temporary catalog placeholder is not an offered model:
 * submitting against it would silently discard a restored effort choice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun EngineFacade.observeStudioModels(
    selections: ModelSelections,
    sources: AuthSources,
    readiness: ExactRouteReadiness,
): Flow<List<StudioModel>> {
    val facade = this
    return combine(
        selections.observe(),
        bindings.state,
        engines.state,
        sources.state,
        readiness.routes,
    ) { selected, saved, catalog, auth, _ ->
        selected.bindings.mapNotNull { enabled ->
            facade.observationsOf(enabled, saved, catalog, auth, readiness)
        }
    }.flatMapLatest { observations ->
        if (observations.isEmpty()) flowOf(emptyList()) else combine(observations) { it.flatMap { models -> models } }
    }
}

/** Loaded cached models of one selected binding, or null when the route itself is not confirmed. */
private fun EngineFacade.observationsOf(
    enabled: BindingModels,
    bindings: List<EngineBinding>,
    engines: List<EngineInfo>,
    auth: List<AuthSource>,
    readiness: ExactRouteReadiness,
): Flow<List<StudioModel>>? {
    val binding = bindings.firstOrNull { it.id == enabled.binding && it.isEnabled } ?: return null
    val engine = engines.firstOrNull { it.descriptor.id == binding.engine } ?: return null
    if (engine.availability != EngineAvailability.Available) return null
    val source = auth.firstOrNull { it.info.id == binding.authSource } ?: return null
    return models.observe(binding.engine, binding.id).filter { it.isLoaded }.map { snapshot ->
        snapshot.offeredModels(enabled, binding, engine, source, readiness)
    }
}

private fun ModelCatalogSnapshot.offeredModels(
    enabled: BindingModels,
    binding: EngineBinding,
    engine: EngineInfo,
    source: AuthSource,
    readiness: ExactRouteReadiness,
): List<StudioModel> = enabled.models.mapNotNull { model ->
    val target = EngineTarget(binding.engine, binding.id, model)
    if (readiness.availability(target) != RouteAvailability.Available) return@mapNotNull null
    val info = models.firstOrNull { it.target == target } ?: return@mapNotNull null
    studioModel(
        target,
        info,
        engine.descriptor.title,
        source.info.label,
        engine.descriptor.isLocalWorkspaceSupported,
        isTrustSupported = AppliesTrustLevels.id in engine.descriptor.declaredFeatures,
        isModelSwitchSupported = engine.descriptor.declaredFeatures.supportsStudioModelSwitch(),
    )
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
        contextLimitTokens = info?.contextLimitTokens,
        inputSupport = info?.inputSupport ?: io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport(),
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
