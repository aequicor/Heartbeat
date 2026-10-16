package io.aequicor.heartbeat.feature.adaptivesubagents.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget

/**
 * Profile service choosing the model configuration of a subagent: engine × connection × model × reasoning effort
 * among the profile's enabled models, by the request's priority of quality, speed and cost (see [SubagentRequest]).
 * Scoring of descriptions (or name heuristics) builds a shortlist; the LLM router, when on, picks among it by the
 * task text. Callers keep their previous behavior on [ModelChoice.Inherit]. Never throws except cancellation;
 * the task text is never logged.
 */
public interface AdaptiveModels {
    /** Chooses one configuration for a subagent of [context]. */
    public suspend fun select(request: SubagentRequest, context: SelectionContext): ModelChoice

    /** The scored shortlist without the router, for showing why a configuration would win. Empty when off. */
    public suspend fun preview(request: SubagentRequest, context: SelectionContext): List<SelectionOption>
}

/** Outcome of [AdaptiveModels.select]. */
public sealed interface ModelChoice {
    /**
     * Run the subagent on [target] at [reasoningEffort] (null — the model's default). [rationale] explains the choice
     * to the agent and the user; [alternatives] counts the other shortlisted configurations.
     */
    public data class Selected(
        val target: EngineTarget,
        val reasoningEffort: String?,
        val title: String,
        val ratings: Ratings,
        val rationale: String,
        val source: ChoiceSource,
        val alternatives: Int,
    ) : ModelChoice

    /** No adaptive choice: the caller keeps the parent's model and the profile's effort. */
    public data class Inherit(val reason: InheritReason) : ModelChoice
}

/** What decided a [ModelChoice.Selected]. */
public enum class ChoiceSource {
    /** Scoring alone (one candidate, a clear winner or the router off). */
    Scoring,

    /** The LLM router picked from the shortlist. */
    Router,

    /** The router failed; the best scored configuration was taken. */
    RouterFallback,
}

/** Why selection kept the inherited model. */
public enum class InheritReason {
    /** Adaptive subagents are turned off. */
    Disabled,

    /** No enabled model fits the request and host. */
    NoCandidates,

    /** The catalog or library could not be read; the caller keeps its inherited configuration. */
    Unavailable,
}

/** Where the ratings of an option come from. */
public enum class RatingSource {
    /** The description of this very model. */
    Described,

    /** The description of the same model on another engine. */
    Borrowed,

    /** The name baseline: nobody described the model. */
    Heuristic,
}

/** One scored configuration of a preview. */
public data class SelectionOption(
    val target: EngineTarget,
    val reasoningEffort: String?,
    val title: String,
    val ratings: Ratings,
    val ratingSource: RatingSource,
    val score: Double,
)
