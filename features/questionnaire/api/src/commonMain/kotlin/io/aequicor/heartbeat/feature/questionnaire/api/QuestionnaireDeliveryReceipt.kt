package io.aequicor.heartbeat.feature.questionnaire.api

import kotlinx.serialization.Serializable

/**
 * In-memory ownership of a removed publication awaiting final delivery acknowledgement.
 * Only the matching [token] may recover or complete it; generation history alone is not authority.
 * This is not a durable outbox: process death loses receipts along with the machine.
 */
@Serializable
public data class QuestionnaireDeliveryReceipt(
    val questionnaire: Questionnaire,
    val generation: String,
    val token: String,
) {
    /** Memory bounds for machine-owned metadata. */
    public companion object {
        /** Bound on preparing deliveries plus unacknowledged receipts. Admission reserves a slot before IO. */
        public const val LIMIT: Int = 256
    }
}
