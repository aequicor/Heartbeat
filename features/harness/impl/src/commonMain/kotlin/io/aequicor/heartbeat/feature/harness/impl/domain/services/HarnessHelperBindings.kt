package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId

/** Durable ownership proof, independent of opaque handoff text. Creation must be journaled before prompt. */
internal data class HarnessHelperBinding(
    val helper: HelperId,
    val owner: ActionId,
    val harness: HarnessId,
    val attachRequest: RequestId,
    val session: SessionRef? = null,
) {
    init {
        require(attachRequest.value.isNotBlank()) { "Missing helper attachment request" }
    }
    override fun toString(): String = "HarnessHelperBinding(***)"
}

/**
 * Immutable helper identity with one bound native session. Repeating an initial bind after session binding is
 * idempotent; changing any ownership or attachment identity fails. Survives workflow result retention because
 * the helper chat can receive a later recovery request. Only proven cascade or profile wipe may remove proof.
 */
internal interface HarnessHelperBindings {
    suspend fun bind(binding: HarnessHelperBinding)
    suspend fun lookup(helper: HelperId): HarnessHelperBinding?
    suspend fun bindSession(helper: HelperId, owner: ActionId, session: SessionRef): HarnessHelperBinding
}
