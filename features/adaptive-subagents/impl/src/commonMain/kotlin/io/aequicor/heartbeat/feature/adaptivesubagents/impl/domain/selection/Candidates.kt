package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection

import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionLimits
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentHost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentNeed
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget

/** What an engine can do for a subagent, read from its capabilities without opening a session. */
internal data class EngineAbilities(
    val isAvailable: Boolean = true,
    val isCreatable: Boolean = true,
    val isResumable: Boolean = true,
    val isTrustApplied: Boolean = true,
    val isWorkspaceSupported: Boolean = true,
)

/**
 * One configuration a subagent could run on: an enabled model route at one advertised [effort] (null when the model
 * has no selectable effort). [efforts] and [defaultEffort] come from the model catalog.
 */
internal data class Candidate(
    val target: EngineTarget,
    val effort: String?,
    val title: String,
    val efforts: List<String> = emptyList(),
    val defaultEffort: String? = null,
    val contextTokens: Long? = null,
    val hasImageInput: Boolean = false,
    val hasPdfInput: Boolean = false,
    val abilities: EngineAbilities = EngineAbilities(),
)

/** One candidate per advertised effort of a model, or one without effort. */
internal fun expand(
    target: EngineTarget,
    title: String,
    efforts: List<String>,
    defaultEffort: String?,
    template: Candidate,
): List<Candidate> {
    val base = template.copy(target = target, title = title, efforts = efforts, defaultEffort = defaultEffort)
    return if (efforts.isEmpty()) listOf(base.copy(effort = null)) else efforts.map { base.copy(effort = it) }
}

/** Whether [candidate] can serve the request at all; soft preferences are scored instead. */
internal fun isEligible(
    candidate: Candidate,
    request: SubagentRequest,
    context: SelectionContext,
    preferences: SubagentPreferences,
): Boolean {
    val parent = context.parentTarget
    return candidate.abilities.serves(context) && candidate.accepts(request) &&
        candidate.target !in preferences.excluded &&
        (preferences.isCrossEngineAllowed || parent == null || parent.engine == candidate.target.engine)
}

/** Organism cells are resumed by the organism; helpers need the host's trust cap applied to every turn. */
private fun EngineAbilities.serves(context: SelectionContext): Boolean = isAvailable && isCreatable &&
    (context.host != SubagentHost.OrganismCell || isResumable) &&
    (context.host == SubagentHost.OrganismCell || isTrustApplied) &&
    (context.workspace == null || isWorkspaceSupported)

/** An unknown context window passes the long-context need; scoring penalises it instead. */
private fun Candidate.accepts(request: SubagentRequest): Boolean =
    (SubagentNeed.Images !in request.needs || hasImageInput) &&
        (SubagentNeed.Pdf !in request.needs || hasPdfInput) &&
        (SubagentNeed.LongContext !in request.needs || (contextTokens ?: Long.MAX_VALUE) >= LONG)

private const val LONG = SelectionLimits.LONG_CONTEXT_TOKENS
