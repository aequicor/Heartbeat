package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection

import io.aequicor.heartbeat.feature.adaptivesubagents.api.CostMode
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.RatingSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionLimits
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentComplexity
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentNeed
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A candidate with its ratings, the description they came from, matched task tags and score. */
internal data class ScoredCandidate(
    val candidate: Candidate,
    val ratings: Ratings,
    val basis: RatingSource,
    val profile: ModelProfile?,
    val matchedTags: Set<String>,
    val score: Double,
)

/**
 * Deterministic selection: eligible candidates ranked by the weighted ratings of the requested priority, penalised
 * below the quality the complexity needs, for overkill on simple tasks, for an effort far from the complexity, and
 * for guessed ratings; task tags and the parent's own connection add a little. At most two configurations of one
 * model route and [SelectionLimits.SHORTLIST] in total; ties keep the catalog order.
 */
internal fun shortlist(
    candidates: List<Candidate>,
    request: SubagentRequest,
    context: SelectionContext,
    profiles: List<ModelProfile>,
    preferences: SubagentPreferences,
): List<ScoredCandidate> {
    val priority = request.priority ?: preferences.defaultPriority
    val ranked = candidates.asSequence()
        .filter { isEligible(it, request, context, preferences) }
        .map { score(it, request, context, priority, rate(it, profiles, preferences)) }
        .sortedByDescending { it.score }
        .toList()
    return ranked.filterIndexed { index, scored ->
        ranked.take(index).count { it.candidate.target == scored.candidate.target } < PER_TARGET
    }.take(SelectionLimits.SHORTLIST)
}

/** Ratings of [candidate] from its description, a borrowed one or the name baseline, corrected by billing. */
internal fun rate(candidate: Candidate, profiles: List<ModelProfile>, preferences: SubagentPreferences): Rated {
    val key = ModelKey.of(candidate.target)
    val own = profiles.firstOrNull { it.key == key }
    val borrowed = own ?: profiles.firstOrNull { normalizedModel(it.key.model) == normalizedModel(key.model) }
    val basis = when {
        own != null -> RatingSource.Described
        borrowed != null -> RatingSource.Borrowed
        else -> RatingSource.Heuristic
    }
    val variant = borrowed?.variants?.firstOrNull { it.effort == candidate.effort }
    val raw = variant?.traits?.ratings?.raw()
        ?: (borrowed?.base?.ratings ?: baselineRatings(key.model, candidate.title))
            .atEffort(candidate.effort, candidate.defaultEffort, candidate.efforts)
    val billed = raw.billed(preferences.costOf(candidate.target.binding))
    val tags = borrowed?.tags.orEmpty() + baselineTags(key.model, candidate.title)
    return Rated(billed.clamped(), billed, basis, borrowed, tags)
}

/** Shown [ratings], the [raw] ones scored, their basis and the tags a candidate is known for. */
internal data class Rated(
    val ratings: Ratings,
    val raw: RawRatings,
    val basis: RatingSource,
    val profile: ModelProfile?,
    val tags: Set<String>,
)

private fun score(
    candidate: Candidate,
    request: SubagentRequest,
    context: SelectionContext,
    priority: SubagentPriority,
    rated: Rated,
): ScoredCandidate {
    val (quality, speed, cost) = WEIGHTS.getValue(priority)
    val ratings = rated.raw
    val matched = request.tags.map(String::lowercase).filter { it in rated.tags }.toSet()
    var score = quality * ratings.quality + speed * ratings.speed + cost * ratings.cost
    score -= SHORTFALL * max(0, FLOOR.getValue(request.complexity) - ratings.quality)
    if (request.complexity == SubagentComplexity.Low && ratings.quality >= Ratings.MAX && ratings.speed <= 2) {
        score -= OVERKILL
    }
    if (candidate.effort != null) {
        val distance = abs(effortRank(candidate.effort, candidate.efforts) - EFFORT.getValue(request.complexity))
        score -= EFFORT_DISTANCE * distance
    }
    if (SubagentNeed.LongContext in request.needs && candidate.contextTokens == null) score -= UNKNOWN_CONTEXT
    score += TAG * min(MAX_TAGS, matched.size) + BASIS.getValue(rated.basis)
    val parent = context.parentTarget
    if (parent != null && parent.engine == candidate.target.engine && parent.binding == candidate.target.binding) {
        score += PARENT_ROUTE
    }
    return ScoredCandidate(candidate, rated.ratings, rated.basis, rated.profile, matched, score.rounded())
}

private fun RawRatings.billed(mode: CostMode): RawRatings = when (mode) {
    CostMode.Metered -> this
    CostMode.Subscription -> copy(cost = max(cost, SUBSCRIPTION_COST))
    CostMode.Local, CostMode.Free -> copy(cost = Ratings.MAX)
}

/** Three decimals keep scores comparable in tests and in the rationale. */
private fun Double.rounded(): Double = (this * ROUNDING).roundToInt() / ROUNDING

private data class Weights(val quality: Double, val speed: Double, val cost: Double)

private val WEIGHTS = mapOf(
    SubagentPriority.Quality to Weights(0.7, 0.15, 0.15),
    SubagentPriority.Balanced to Weights(0.4, 0.3, 0.3),
    SubagentPriority.Speed to Weights(0.15, 0.7, 0.15),
    SubagentPriority.Cost to Weights(0.15, 0.15, 0.7),
)
private val FLOOR = mapOf(SubagentComplexity.Low to 2, SubagentComplexity.Medium to 3, SubagentComplexity.High to 4)
private val EFFORT = mapOf(SubagentComplexity.Low to 1, SubagentComplexity.Medium to 2, SubagentComplexity.High to 3)
private val BASIS = mapOf(RatingSource.Described to 0.0, RatingSource.Borrowed to -0.1, RatingSource.Heuristic to -0.25)
private const val PER_TARGET = 2
private const val SHORTFALL = 0.75
private const val OVERKILL = 0.3
private const val EFFORT_DISTANCE = 0.2
private const val UNKNOWN_CONTEXT = 0.3
private const val TAG = 0.25
private const val MAX_TAGS = 2
private const val PARENT_ROUTE = 0.1
private const val SUBSCRIPTION_COST = 4
private const val ROUNDING = 1000.0
