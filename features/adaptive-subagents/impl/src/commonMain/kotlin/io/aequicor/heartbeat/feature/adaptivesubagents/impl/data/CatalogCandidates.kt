package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.Candidate
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.EngineAbilities
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.expand
import io.aequicor.heartbeat.feature.aiengine.connections.api.BindingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import kotlinx.coroutines.flow.first

/**
 * The configurations a subagent may run on: models the user enabled on enabled connections, joined with the cached
 * model catalog (never starting discovery), one per advertised effort. Engine abilities come from static
 * descriptors, so reading candidates opens no session.
 */
@Inject
internal class CatalogCandidates(
    private val facade: Lazy<EngineFacade>,
    private val selections: Lazy<ModelSelections>,
) {
    private val log = Log.tag("CatalogCandidates")

    suspend fun candidates(): List<Candidate> {
        val selection = selections.value.observe().first()
        val facade = facade.value
        val bindings = facade.bindings.state.value
        val engines = facade.engines.state.value
        val result = selection.bindings.flatMap<BindingModels, Candidate> { enabled ->
            val binding = bindings.firstOrNull { it.id == enabled.binding && it.isEnabled }
                ?: return@flatMap emptyList()
            val engine = engines.firstOrNull { it.descriptor.id == binding.engine } ?: return@flatMap emptyList()
            val catalog = facade.models.observe(binding.engine, binding.id).value.models
            enabled.models.sortedBy { it.value }.flatMap { model ->
                val target = EngineTarget(binding.engine, binding.id, model)
                val info = catalog.firstOrNull { it.target == target }
                val template = Candidate(
                    target,
                    effort = null,
                    title = info?.title?.takeIf(String::isNotBlank) ?: model.value,
                    contextTokens = info?.contextLimitTokens,
                    hasImageInput = info?.inputSupport?.imageMediaTypes.orEmpty().isNotEmpty(),
                    hasPdfInput = PDF in info?.inputSupport?.resourceMediaTypes.orEmpty(),
                    abilities = engine.abilities(),
                )
                expand(target, template.title, info?.reasoningEfforts.orEmpty(), info?.defaultReasoningEffort, template)
            }
        }
        log.d { "subagent candidates: ${result.size}" }
        return result
    }
}

private fun EngineInfo.abilities(): EngineAbilities {
    val features = descriptor.declaredFeatures
    return EngineAbilities(
        isAvailable = availability is EngineAvailability.Available || availability is EngineAvailability.Unknown,
        isCreatable = CreatesSessions.id in features,
        isResumable = ResumesSessions.id in features,
        isTrustApplied = AppliesTrustLevels.id in features,
        isWorkspaceSupported = descriptor.isLocalWorkspaceSupported,
    )
}

private const val PDF = "application/pdf"
