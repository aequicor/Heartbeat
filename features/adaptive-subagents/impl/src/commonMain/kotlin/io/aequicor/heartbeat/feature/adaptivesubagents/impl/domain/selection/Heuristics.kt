package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection

import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId

/**
 * A baseline for a model nobody described, from tokens of its id and title: small/fast families, large/strong ones
 * and the middle. Coarse on purpose; any description replaces it.
 */
internal fun baselineRatings(model: ModelId, title: String): Ratings {
    val name = "${model.value} $title".lowercase()
    return when {
        SMALL.containsMatchIn(name) || SMALL_PARAMS.containsMatchIn(name) -> SMALL_RATINGS
        LARGE.containsMatchIn(name) || LARGE_GPT.containsMatchIn(name) -> LARGE_RATINGS
        MID.any { it in name } -> MID_RATINGS
        else -> Ratings.NEUTRAL
    }
}

/** Task tags a model's name implies. */
internal fun baselineTags(model: ModelId, title: String): Set<String> {
    val name = "${model.value} $title".lowercase()
    return if (CODE.any { it in name }) setOf("coding") else emptySet()
}

/**
 * Rank of an effort on a common scale 0..4 (minimal, low, medium, high, maximal). Unknown ids are placed by their
 * position among the model's [efforts]; null is the model's default.
 */
internal fun effortRank(effort: String?, efforts: List<String>): Int {
    val known = effort?.let { KNOWN_RANKS[it.lowercase()] }
    val index = efforts.indexOf(effort)
    return when {
        known != null -> known
        effort == null || index < 0 || efforts.size < 2 -> MEDIUM_RANK
        else -> (index * MAX_RANK + (efforts.size - 1) / 2) / (efforts.size - 1)
    }
}

/**
 * Ratings before clamping to 1..5: a model already rated 5 still gains from a higher effort, so scoring keeps one
 * step of headroom on either side ([RAW_RANGE]); shown ratings are [clamped].
 */
internal data class RawRatings(val quality: Int, val speed: Int, val cost: Int) {
    /** The shown 1..5 ratings. */
    fun clamped(): Ratings = Ratings(
        quality.coerceIn(Ratings.RANGE),
        speed.coerceIn(Ratings.RANGE),
        cost.coerceIn(Ratings.RANGE),
    )
}

/** Exactly described ratings. */
internal fun Ratings.raw(): RawRatings = RawRatings(quality, speed, cost)

/** Ratings at [effort] when the model's default effort is described by these ratings. */
internal fun Ratings.atEffort(effort: String?, defaultEffort: String?, efforts: List<String>): RawRatings {
    val from = DELTAS[effortRank(defaultEffort, efforts)]
    val to = DELTAS[effortRank(effort, efforts)]
    return RawRatings(
        (quality + to.quality - from.quality).coerceIn(RAW_RANGE),
        (speed + to.speed - from.speed).coerceIn(RAW_RANGE),
        (cost + to.cost - from.cost).coerceIn(RAW_RANGE),
    )
}

private val RAW_RANGE = Ratings.MIN - 1..Ratings.MAX + 1

/** A model id comparable across engines: no provider prefix, `:tag`, or date suffix. */
internal fun normalizedModel(model: ModelId): String = model.value.lowercase()
    .substringAfterLast('/')
    .substringBefore(':')
    .replace(DATE_SUFFIX, "")

private data class Delta(val quality: Int, val speed: Int, val cost: Int)

private const val MEDIUM_RANK = 2
private const val MAX_RANK = 4
private val KNOWN_RANKS = mapOf(
    "none" to 0,
    "minimal" to 0,
    "low" to 1,
    "medium" to 2,
    "high" to 3,
    "xhigh" to 4,
    "max" to 4,
    "maximum" to 4,
)
private val SMALL_RATINGS = Ratings(quality = 3, speed = 5, cost = 5)
private val MID_RATINGS = Ratings(quality = 4, speed = 3, cost = 3)
private val LARGE_RATINGS = Ratings(quality = 5, speed = 2, cost = 2)
private val DELTAS = listOf(Delta(-1, 2, 1), Delta(-1, 1, 1), Delta(0, 0, 0), Delta(1, -1, -1), Delta(1, -2, -2))
private val SMALL = Regex("""haiku|\b(mini|nano|lite|small|tiny)\b""")
private val SMALL_PARAMS = Regex("""\b(\d|1[0-4])(\.\d)?b\b""")
private val LARGE = Regex("""opus|ultra|405b|[-\s]pro\b|\bo[13]\b""")
private val LARGE_GPT = Regex("""gpt-5(\.\d)?(?![\w.-]*(mini|nano))""")
private val MID = listOf("sonnet", "flash", "gpt-4", "deepseek", "glm", "qwen", "mistral", "llama", "gemini")
private val CODE = listOf("codex", "coder", "code")
private val DATE_SUFFIX = Regex("""-\d{8}$|-\d{4}-\d{2}-\d{2}$""")
