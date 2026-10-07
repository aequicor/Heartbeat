package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator

/** Exact native target and immutable host-stamped causes; carries no prompt text or execution authority. */
public data class RequestOriginAttempt(
    val session: SessionRef,
    val request: RequestId,
    val causes: Set<RequestInitiator>,
) {
    override fun toString(): String = "RequestOriginAttempt(***)"
}

/**
 * Monotone provenance bookkeeping for host-submitted prompts, including graph helpers without helper ownership.
 * Hosts resolve this set lazily for nonempty causes and await every observer before prompt hooks and the existing
 * native submission gate. This is not an authorization gate: it cannot grant permission or replace live admission.
 */
public fun interface ScheduledRequestOriginObserver {
    /**
     * Durably merge restrictions of exact causes with any prior target restrictions. Repeated calls are idempotent;
     * unknown ancestry is neutral. A disabled feature must still relay its saved restrictions. Never infer causes
     * from text, latest turns or helper identity. Failure or cancellation propagates and prevents native submission.
     */
    public suspend fun record(attempt: RequestOriginAttempt)
}
