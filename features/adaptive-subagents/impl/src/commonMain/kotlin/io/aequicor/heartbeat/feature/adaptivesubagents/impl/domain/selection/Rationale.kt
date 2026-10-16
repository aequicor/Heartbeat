package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection

import io.aequicor.heartbeat.feature.adaptivesubagents.api.RatingSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority

/** A short English explanation of a scored configuration for the agent and the user. */
internal fun ScoredCandidate.rationale(priority: SubagentPriority): String = buildString {
    append(candidate.title)
    candidate.effort?.let { append(", effort ").append(it) }
    append(": quality ").append(ratings.quality)
    append(", speed ").append(ratings.speed)
    append(", cost ").append(ratings.cost)
    append(" (").append(SOURCES.getValue(basis)).append(')')
    if (matchedTags.isNotEmpty()) append("; suits ").append(matchedTags.sorted().joinToString())
    append("; priority ").append(priority.name.lowercase())
}

private val SOURCES = mapOf(
    RatingSource.Described to "from its description",
    RatingSource.Borrowed to "from the same model on another engine",
    RatingSource.Heuristic to "estimated from its name",
)
