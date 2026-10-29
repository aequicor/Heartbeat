package io.aequicor.heartbeat.feature.questionnaire.api

import kotlinx.serialization.Serializable

/**
 * Native host assertion, separate from opaque context and never decoded from model or screen JSON.
 * Hosts must explicitly mark automated answers [Auto]; omitted provenance always means [Manual].
 * This is a trusted in-process contract, not an authorization boundary against arbitrary native code.
 */
public enum class QuestionnaireAnswerOrigin {
    Manual,
    Auto,
}

/**
 * Lifetime manual-source barrier, including earlier batches and withdrawn publications.
 * At capacity (or with an oversized source), blocking another source permanently blocks all automation.
 * No eviction, restore seal, delivery, or empty queue can reopen automation on this machine.
 * The barrier is not durable across process death; the owner must also retain its durable context lock.
 */
@Serializable
public data class QuestionnaireAutoBarrier(
    val sources: Set<String> = emptySet(),
    val isGloballyBlocked: Boolean = false,
) {
    /** Safe for host preflight; the Answer transition always rechecks this atomically. */
    public fun isBlocked(source: String): Boolean = isGloballyBlocked || source in sources

    internal fun block(source: String): QuestionnaireAutoBarrier = when {
        isBlocked(source) -> this

        sources.size >= LIMIT || source.length > MAX_SOURCE_LENGTH ->
            copy(sources = emptySet(), isGloballyBlocked = true)

        else -> copy(sources = sources + source)
    }

    /** Memory bounds for machine-owned metadata. */
    public companion object {
        /** Maximum retained source identities; crossing the limit activates the permanent global barrier. */
        public const val LIMIT: Int = 256

        /** Bounds memory per retained source in UTF-16 code units. */
        public const val MAX_SOURCE_LENGTH: Int = 4096
    }
}
