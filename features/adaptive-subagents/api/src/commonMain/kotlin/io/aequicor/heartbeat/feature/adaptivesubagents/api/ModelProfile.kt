package io.aequicor.heartbeat.feature.adaptivesubagents.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** A model of one engine, shared by all of the engine's connections: what a description is about. */
@Serializable
public data class ModelKey(val engine: EngineId, val model: ModelId) {
    /** Model routes. */
    public companion object {
        /** The model of [target], whatever its connection. */
        public fun of(target: EngineTarget): ModelKey = ModelKey(target.engine, target.model)
    }
}

/**
 * How a model configuration compares on the three axes, each 1..5: higher [quality] is better work, higher [speed]
 * is faster, higher [cost] is CHEAPER (5 — free or nearly so). Comparable across all configurations of a profile.
 */
@Serializable
public data class Ratings(val quality: Int, val speed: Int, val cost: Int) {
    init {
        require(quality in RANGE && speed in RANGE && cost in RANGE) { "Ratings are 1..5" }
    }

    /** Bounds of a rating. */
    public companion object {
        /** Lowest rating. */
        public const val MIN: Int = 1

        /** Highest rating. */
        public const val MAX: Int = 5

        /** Valid ratings. */
        public val RANGE: IntRange = MIN..MAX

        /** Neutral ratings of an unknown model. */
        public val NEUTRAL: Ratings = Ratings(3, 3, 3)
    }
}

/** What a configuration is like: its ratings, strengths, weaknesses and the tasks it suits. */
@Serializable
public data class Traits(
    val ratings: Ratings = Ratings.NEUTRAL,
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val useCases: List<String> = emptyList(),
) {
    override fun toString(): String = "Traits(ratings=$ratings)"
}

/**
 * The model at one reasoning [effort] (native id from `ModelInfo.reasoningEfforts`). [params] reserves other
 * per-configuration model parameters (e.g. a mode); selection applies only the effort today.
 */
@Serializable
public data class ProfileVariant(
    val effort: String,
    val traits: Traits,
    val params: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "ProfileVariant(effort=$effort, traits=$traits)"
}

/** Facts from a public catalog (models.dev): limits, list prices in USD per million tokens and input kinds. */
@Serializable
public data class ModelFacts(
    val contextTokens: Long? = null,
    val outputTokens: Long? = null,
    val inputUsdPerMTok: Double? = null,
    val outputUsdPerMTok: Double? = null,
    val modalities: Set<String> = emptySet(),
    val releaseDate: String? = null,
    val source: String,
    val fetchedAt: Instant,
)

/** Who wrote a description. */
@Serializable
public enum class ProfileSource {
    /** Written or last edited by the user. */
    Manual,

    /** Written by the research pipeline and not edited since. */
    Research,
}

/**
 * The profile's description of a model of one engine: a [summary], [tags] of tasks it suits, the [base] traits (its
 * default effort) and [variants] per reasoning effort, optional catalog [facts] and the [sources] it was written from.
 * [revision] is set by the library on every save and guards concurrent edits.
 */
@Serializable
public data class ModelProfile(
    val key: ModelKey,
    val title: String,
    val summary: String = "",
    val tags: Set<String> = emptySet(),
    val base: Traits = Traits(),
    val variants: List<ProfileVariant> = emptyList(),
    val facts: ModelFacts? = null,
    val source: ProfileSource = ProfileSource.Manual,
    val sources: List<String> = emptyList(),
    val updatedAt: Instant,
    val revision: Long = 0,
) {
    /** Traits of the configuration at [effort]: its variant, else the base. */
    public fun traitsFor(effort: String?): Traits = variants.firstOrNull { it.effort == effort }?.traits ?: base

    override fun toString(): String =
        "ModelProfile(key=$key, source=$source, variants=${variants.size}, revision=$revision)"
}

/** How a connection is billed; it corrects the cost rating of every model reached through it. */
@Serializable
public enum class CostMode {
    /** Pay per token at list price. */
    Metered,

    /** A flat subscription: marginal cost is low until its limits. */
    Subscription,

    /** Runs on this machine. */
    Local,

    /** Free of charge. */
    Free,
}

/** What a subagent's configuration is optimised for. */
@Serializable
public enum class SubagentPriority { Quality, Balanced, Speed, Cost }

/** Billing of one connection. */
@Serializable
public data class ConnectionCost(val binding: EngineBindingId, val mode: CostMode)

/**
 * Profile preferences of adaptive subagents: the [defaultPriority] when a caller names none, the models of the LLM
 * router and of the researcher (null — the profile's default model), configurations never chosen, whether a
 * subagent may run on another engine than its parent, and connection billing. When cross-engine selection is
 * forbidden, an unknown parent engine leaves the subagent on its inherited configuration.
 */
@Serializable
public data class SubagentPreferences(
    val defaultPriority: SubagentPriority = SubagentPriority.Balanced,
    val routerTarget: EngineTarget? = null,
    val researcherTarget: EngineTarget? = null,
    val excluded: Set<EngineTarget> = emptySet(),
    val isCrossEngineAllowed: Boolean = true,
    val connectionCosts: List<ConnectionCost> = emptyList(),
) {
    /** Billing of [binding], metered when unknown. */
    public fun costOf(binding: EngineBindingId): CostMode =
        connectionCosts.firstOrNull { it.binding == binding }?.mode ?: CostMode.Metered
}

/** Bounds of descriptions; texts are written by users and researchers, so every list and string is capped. */
public object ProfileLimits {
    /** Longest title. */
    public const val MAX_TITLE: Int = 80

    /** Longest summary. */
    public const val MAX_SUMMARY: Int = 600

    /** Most strengths, weaknesses or use cases. */
    public const val MAX_ITEMS: Int = 6

    /** Longest strength, weakness or use case. */
    public const val MAX_ITEM: Int = 160

    /** Most tags. */
    public const val MAX_TAGS: Int = 10

    /** Most effort variants. */
    public const val MAX_VARIANTS: Int = 8

    /** Most reserved parameters of a variant. */
    public const val MAX_PARAMS: Int = 8

    /** Most source links. */
    public const val MAX_SOURCES: Int = 12

    /** Longest source link. */
    public const val MAX_SOURCE: Int = 500

    /** Most descriptions in a profile. */
    public const val MAX_PROFILES: Int = 256

    /** A tag: lowercase words joined by dashes. */
    public val TAG: Regex = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    /** Longest tag. */
    public const val MAX_TAG: Int = 24
}

/** Tags of tasks a description may name; custom tags follow [ProfileLimits.TAG]. */
public object ModelTags {
    /** The known vocabulary, offered to users and researchers. */
    public val KNOWN: List<String> = listOf(
        "coding", "refactoring", "review", "debugging", "research", "writing", "docs", "data", "math", "ui",
        "planning", "quick-edits", "long-context", "vision", "agentic",
    )
}

/** Why a description cannot be saved. */
public enum class ProfileProblem {
    /** A text exceeds its limit. */
    TooLong,

    /** A list exceeds its limit. */
    TooMany,

    /** A tag is not lowercase words joined by dashes. */
    InvalidTag,

    /** Two variants describe the same effort, or an effort is blank. */
    DuplicateVariant,

    /** A source is not an http(s) link. */
    InvalidSource,

    /** A text contains control or invisible characters. */
    InvisibleText,
}

/** Trims every text, lowercases tags and drops blank and repeated items. */
public fun ModelProfile.normalized(): ModelProfile = copy(
    title = title.trim(),
    summary = summary.trim(),
    tags = tags.map { it.trim().lowercase() }.filter(String::isNotEmpty).toSet(),
    base = base.normalized(),
    variants = variants.map { it.copy(effort = it.effort.trim(), traits = it.traits.normalized()) },
    sources = sources.clean(),
)

/** The first reason a [normalized] description cannot be saved, or null when it is valid. */
public fun ModelProfile.problem(): ProfileProblem? {
    val traits = listOf(base) + variants.map { it.traits }
    return when {
        hasHiddenText(traits) -> ProfileProblem.InvisibleText

        isTooLong(traits) -> ProfileProblem.TooLong

        isTooMany(traits) -> ProfileProblem.TooMany

        tags.any { it.length > ProfileLimits.MAX_TAG || !ProfileLimits.TAG.matches(it) } -> ProfileProblem.InvalidTag

        variants.any { it.effort.isBlank() } || variants.distinctBy { it.effort }.size != variants.size ->
            ProfileProblem.DuplicateVariant

        sources.any { !it.startsWith("https://") && !it.startsWith("http://") } -> ProfileProblem.InvalidSource

        else -> null
    }
}

private fun ModelProfile.hasHiddenText(traits: List<Traits>): Boolean {
    val texts = listOf(title, summary) + sources + traits.flatMap { it.strengths + it.weaknesses + it.useCases } +
        variants.flatMap { it.params.keys + it.params.values }
    return texts.any(::hasInvisible)
}

private fun ModelProfile.isTooLong(traits: List<Traits>): Boolean =
    title.length > ProfileLimits.MAX_TITLE || summary.length > ProfileLimits.MAX_SUMMARY ||
        traits.any { it.isTooLong() } || sources.any { it.length > ProfileLimits.MAX_SOURCE }

private fun ModelProfile.isTooMany(traits: List<Traits>): Boolean =
    tags.size > ProfileLimits.MAX_TAGS || variants.size > ProfileLimits.MAX_VARIANTS ||
        sources.size > ProfileLimits.MAX_SOURCES || variants.any { it.params.size > ProfileLimits.MAX_PARAMS } ||
        traits.any {
            it.strengths.size > ProfileLimits.MAX_ITEMS || it.weaknesses.size > ProfileLimits.MAX_ITEMS ||
                it.useCases.size > ProfileLimits.MAX_ITEMS
        }

private fun Traits.isTooLong(): Boolean = (strengths + weaknesses + useCases).any { it.length > ProfileLimits.MAX_ITEM }

private fun Traits.normalized(): Traits =
    copy(strengths = strengths.clean(), weaknesses = weaknesses.clean(), useCases = useCases.clean())

private fun List<String>.clean(): List<String> =
    asSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()

/** Line breaks are allowed in a summary; any other control or format character could hide instructions. */
private fun hasInvisible(text: String): Boolean = text.any {
    it != '\n' && (it.isISOControl() || it.category == CharCategory.FORMAT)
}
