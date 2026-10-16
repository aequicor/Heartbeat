package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.router

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsRouter
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.ScoredCandidate
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.parseRoute
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.routerPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.Fence
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import kotlin.time.Duration.Companion.seconds

/** What the router did with a shortlist. */
internal sealed interface Routed {
    /** Scoring decides: the router is off, has nothing to choose or no model, or the winner is clear. */
    data object Skipped : Routed

    /** The router picked [index] of the shortlist for [rationale]. */
    data class Chosen(val index: Int, val rationale: String) : Routed {
        override fun toString(): String = "Chosen(index=$index)"
    }

    /** The router failed or answered nonsense; the best scored configuration is used. */
    data object Failed : Routed
}

/**
 * The LLM router: one isolated, tool-less prompt on [EngineTarget] reading the task and the shortlist. It runs only
 * when scoring leaves a real choice — two or more configurations within [MARGIN] of the best.
 */
@Inject
internal class ShortlistRouter(private val prompts: Lazy<IsolatedPrompts>, private val toggles: FeatureToggles) {
    private val log = Log.tag("ShortlistRouter")

    suspend fun route(
        shortlist: List<ScoredCandidate>,
        request: SubagentRequest,
        priority: SubagentPriority,
        target: EngineTarget?,
    ): Routed {
        val isClear = shortlist.size < 2 || shortlist[0].score - shortlist[1].score >= MARGIN
        if (target == null || isClear || !toggles.get(AdaptiveSubagentsRouter)) return Routed.Skipped
        val prompt = IsolatedPrompt(
            target,
            routerPrompt(shortlist, request, priority, Fence.random()),
            timeout = TIMEOUT,
            maxAnswerChars = MAX_ANSWER,
        )
        val routed = when (val answer = prompts.value.ask(prompt)) {
            is IsolatedAnswer.Answered -> parseRoute(answer.text, shortlist.size)
                ?.let { Routed.Chosen(it.index, it.rationale) } ?: Routed.Failed

            is IsolatedAnswer.Unavailable -> Routed.Failed.also { log.w { "router unavailable: ${answer.reason}" } }
        }
        log.i { "router over ${shortlist.size} candidates: $routed" }
        return routed
    }

    private companion object {
        const val MARGIN = 0.35
        const val MAX_ANSWER = 2_000
        val TIMEOUT = 20.seconds
    }
}
