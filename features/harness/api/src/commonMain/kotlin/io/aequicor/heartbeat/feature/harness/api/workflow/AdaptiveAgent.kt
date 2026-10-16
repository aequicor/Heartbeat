package io.aequicor.heartbeat.feature.harness.api.workflow

import kotlinx.serialization.Serializable

/** What an adaptive helper's model should favor. */
@Serializable
public enum class AgentPriority {
    /** The best answer, whatever it costs. */
    Quality,

    /** A balance of quality, speed and cost. */
    Balanced,

    /** The fastest answer of acceptable quality. */
    Speed,

    /** The cheapest answer of acceptable quality. */
    Cost,
}

/** How demanding an adaptive helper's task is; it sets the lowest acceptable quality and the preferred effort. */
@Serializable
public enum class AgentComplexity { Low, Medium, High }

/** Inputs an adaptive helper's model must accept. */
@Serializable
public enum class AgentNeed {
    /** Image input. */
    Images,

    /** PDF documents. */
    Pdf,

    /** A long context window. */
    LongContext,
}

/**
 * Asks the host to choose the helper's model configuration — engine, connection, model and reasoning effort —
 * among the models enabled in the profile, by [priority] (null — the profile's default), [complexity], required
 * input [needs] and [tags] of the task kind (for example `coding`, `review`, `research`). The task prompt is read
 * by the host's router. When adaptive subagents are off or nothing fits, the helper keeps its default model.
 * An explicit target wins over this request. The choice is made once, when the helper is created; replay and
 * recovery keep the helper's model. Trust stays bounded by the host whatever model is chosen.
 */
@Serializable
public data class AdaptiveAgent(
    val priority: AgentPriority? = null,
    val complexity: AgentComplexity = AgentComplexity.Medium,
    val needs: Set<AgentNeed> = emptySet(),
    val tags: Set<String> = emptySet(),
)
