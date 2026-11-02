package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Created before publication and retained by the gate, independently of native request correlation. */
internal fun StudioTurnRequest.newRecoveryDelivery(): StudioRecoveryDelivery? =
    if (recoveryRequests.isEmpty()) null else StudioRecoveryDelivery(id, request, Uuid.random().toString())

/** One coordinator-owned observation delivery; its claim is distinct from every historical native request. */
@Serializable
internal data class StudioRecoveryDelivery(val chat: String, val execution: RequestId, val claim: String) {
    init {
        require(chat.isNotBlank() && claim.isNotBlank())
    }

    override fun toString(): String = "StudioRecoveryDelivery(***)"
}

/** Refusal of this delivery's new send, never a statement that the historical native request was not submitted. */
@Serializable
internal enum class StudioRecoveryRefusal { Deferred, Dropped, Cancelled }

/** Created only by the exact gate after it irrevocably seals its pre-entry state. */
internal data class StudioRecoveryNoSendProof(
    val delivery: StudioRecoveryDelivery,
    val refusal: StudioRecoveryRefusal,
) {
    override fun toString(): String = "StudioRecoveryNoSendProof(refusal=$refusal)"
}
