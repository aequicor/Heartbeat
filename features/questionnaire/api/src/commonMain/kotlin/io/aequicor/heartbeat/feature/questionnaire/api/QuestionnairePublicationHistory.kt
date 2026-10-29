package io.aequicor.heartbeat.feature.questionnaire.api

import kotlinx.serialization.Serializable

/**
 * Publication identities and exact initial-restore exclusions, owned by one profile machine.
 *
 * [latest] retains at most [LIMIT] generations in mutation order, including null for withdrawal before publication.
 * Active generations live independently in [QuestionnaireState.Asking.generations]. Eviction never authorizes a
 * conditional absent Ask: it still requires a retained matching generation.
 *
 * Until [isInitialRestoreFinished], [touched] remembers every durable id touched during loading, including evicted
 * withdrawals. Live callers never enter this set because the journal cannot restore them. A successful initial
 * restore atomically seals the machine against every later Restore and clears the exclusions. Thereafter metadata
 * is O(LIMIT + live queue); before successful reconciliation exact exclusions can grow with durable-id churn.
 */
@Serializable
public data class QuestionnairePublicationHistory(
    val latest: Map<QuestionnaireId, String?> = emptyMap(),
    val touched: Set<QuestionnaireId> = latest.keys.filterNot { it.value.startsWith(LIVE_QUESTION_ID_PREFIX) }.toSet(),
    val isInitialRestoreFinished: Boolean = false,
) {
    internal fun record(id: QuestionnaireId, generation: String?): QuestionnairePublicationHistory {
        val updated = (latest - id) + (id to generation)
        val isOverflow = updated.size > LIMIT
        return copy(
            latest = if (isOverflow) updated.entries.drop(updated.size - LIMIT).associate { it.toPair() } else updated,
            touched = if (isInitialRestoreFinished || id.value.startsWith(LIVE_QUESTION_ID_PREFIX) || id in touched) {
                touched
            } else {
                touched + id
            },
        )
    }

    internal fun finishInitialRestore(): QuestionnairePublicationHistory =
        copy(touched = emptySet(), isInitialRestoreFinished = true)

    /** Retention limit for generation identities, independent of exact restore exclusions. */
    public companion object {
        /** Bound on historical generations; active generations and delivery gates are bounded by the live queue. */
        public const val LIMIT: Int = 4096
    }
}
