package io.aequicor.heartbeat.feature.adaptivesubagents.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.Serializable

/** How demanding a subagent's task is; it sets the lowest acceptable quality and the preferred effort. */
@Serializable
public enum class SubagentComplexity { Low, Medium, High }

/** Inputs a subagent's model must accept. */
@Serializable
public enum class SubagentNeed {
    /** Image input. */
    Images,

    /** PDF documents. */
    Pdf,

    /** A context window of at least [SelectionLimits.LONG_CONTEXT_TOKENS]. */
    LongContext,
}

/** Who runs the subagent; the host decides which engine capabilities are required. */
public enum class SubagentHost {
    /** A helper chat of an ordinary studio chat. */
    StudioHelper,

    /** A cell of an organic AI organism. */
    OrganismCell,

    /** A helper of a harness workflow step. */
    WorkflowHelper,

    /** A helper spawned by a harness script. */
    ScriptHelper,
}

/**
 * What a subagent needs: its [task] (read by the router, never logged), the [priority] (null — the profile default),
 * [complexity], required input [needs] and [tags] of the task kind (see [ModelTags]).
 */
public data class SubagentRequest(
    val task: String,
    val priority: SubagentPriority? = null,
    val complexity: SubagentComplexity = SubagentComplexity.Medium,
    val needs: Set<SubagentNeed> = emptySet(),
    val tags: Set<String> = emptySet(),
) {
    override fun toString(): String =
        "SubagentRequest(priority=$priority, complexity=$complexity, needs=$needs, tags=${tags.size})"
}

/** Where the subagent runs: its host, the parent's model (when known) and the execution workspace. */
public data class SelectionContext(
    val host: SubagentHost,
    val parentTarget: EngineTarget?,
    val workspace: WorkspaceRef?,
)

/** Bounds of selection. */
public object SelectionLimits {
    /** Smallest context window that satisfies [SubagentNeed.LongContext]. */
    public const val LONG_CONTEXT_TOKENS: Long = 200_000

    /** Most configurations the router chooses among. */
    public const val SHORTLIST: Int = 3
}
