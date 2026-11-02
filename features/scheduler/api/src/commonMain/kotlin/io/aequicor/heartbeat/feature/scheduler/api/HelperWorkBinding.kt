package io.aequicor.heartbeat.feature.scheduler.api

import kotlinx.serialization.Serializable

/**
 * Host-stamped ownership of one helper request, independent of the conversation's logical role. A lease replaces
 * any incoming binding with its own owner and reservation. Recovery supplies it only from an exact durable work
 * journal; neither chat ancestry, a helper marker nor the latest chat request proves this relationship.
 * This value carries evidence of work attribution, never permission to invoke an operation.
 */
@Serializable
public data class HelperWorkBinding(val owner: ActionId, val reservation: ActionId? = null) {
    override fun toString(): String = "HelperWorkBinding(***)"
}
