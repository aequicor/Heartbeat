package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveModels
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsEnabled
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ChoiceSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.InheritReason
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelChoice
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionOption
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.router.Routed
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.router.ShortlistRouter
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.ScoredCandidate
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.rationale
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.shortlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Selection of subagent configurations: scoring of the cached catalog against the profile's descriptions. A library
 * that is still loading is awaited briefly; an unreadable one leaves heuristics. Choices and counts are logged,
 * the task never.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProfileAdaptiveModels(
    private val catalog: CatalogCandidates,
    private val router: ShortlistRouter,
    // Lazy: injecting the view starts the library machine, which only an enabled feature needs.
    private val library: Lazy<ModelProfilesView>,
    private val toggles: FeatureToggles,
) : AdaptiveModels {
    private val log = Log.tag("AdaptiveModels")

    override suspend fun select(request: SubagentRequest, context: SelectionContext): ModelChoice =
        safely("selection", ModelChoice.Inherit(InheritReason.Unavailable)) { choose(request, context) }

    private suspend fun choose(request: SubagentRequest, context: SelectionContext): ModelChoice {
        if (!toggles.get(AdaptiveSubagentsEnabled)) return ModelChoice.Inherit(InheritReason.Disabled)
        val ready = ready()
        val priority = request.priority ?: ready.preferences.defaultPriority
        val list = shortlist(catalog.candidates(), request, context, ready.profiles, ready.preferences)
        val best = list.firstOrNull()
        if (best == null) {
            log.i { "subagent selection host=${context.host}: no candidates" }
            return ModelChoice.Inherit(InheritReason.NoCandidates)
        }
        val routed = safely("routing", Routed.Failed) {
            val routerTarget = ready.preferences.routerTarget ?: catalog.defaultTarget() ?: context.parentTarget
            router.route(list, request, priority, routerTarget)
        }
        val choice = when (routed) {
            Routed.Skipped -> best.selected(best.rationale(priority), ChoiceSource.Scoring, list.size - 1)

            Routed.Failed -> best.selected(best.rationale(priority), ChoiceSource.RouterFallback, list.size - 1)

            is Routed.Chosen -> list[routed.index].let { chosen ->
                val reason = routed.rationale.ifEmpty { "chosen by the router" }
                chosen.selected("$reason (${chosen.rationale(priority)})", ChoiceSource.Router, list.size - 1)
            }
        }
        log.i {
            "subagent selection host=${context.host} priority=$priority shortlist=${list.size} " +
                "source=${choice.source} chose ${choice.target} effort=${choice.reasoningEffort ?: "default"}"
        }
        return choice
    }

    override suspend fun preview(request: SubagentRequest, context: SelectionContext): List<SelectionOption> =
        safely("preview", emptyList()) {
            if (!toggles.get(AdaptiveSubagentsEnabled)) return@safely emptyList()
            val ready = ready()
            shortlist(catalog.candidates(), request, context, ready.profiles, ready.preferences).map { scored ->
                SelectionOption(
                    scored.candidate.target,
                    scored.candidate.effort,
                    scored.candidate.title,
                    scored.ratings,
                    scored.basis,
                    scored.score,
                )
            }
        }

    /** Dependency errors can quote private task or model text, so only their kind is logged. */
    private suspend fun <T> safely(operation: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(
            IllegalStateException(e::class.simpleName.orEmpty()),
        ) { "subagent $operation unavailable; using fallback" }
        fallback
    }

    private suspend fun ready(): ModelProfilesState.Ready {
        val state = withTimeoutOrNull(LIBRARY_WAIT) {
            library.value.state.first { it is ModelProfilesState.Ready || it is ModelProfilesState.Failed }
        }
        if (state !is ModelProfilesState.Ready) log.w { "model library unavailable; selecting by name heuristics" }
        return state as? ModelProfilesState.Ready ?: ModelProfilesState.Ready()
    }
}

/** The choice of [this] shortlisted configuration. */
internal fun ScoredCandidate.selected(
    rationale: String,
    source: ChoiceSource,
    alternatives: Int,
): ModelChoice.Selected = ModelChoice.Selected(
    candidate.target,
    candidate.effort,
    candidate.title,
    ratings,
    rationale,
    source,
    alternatives,
)

private val LIBRARY_WAIT = 5.seconds
