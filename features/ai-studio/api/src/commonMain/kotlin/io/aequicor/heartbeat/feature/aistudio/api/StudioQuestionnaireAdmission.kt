package io.aequicor.heartbeat.feature.aistudio.api

/**
 * Source-owned durable barrier for substantive manual answers. Return only after previous human context can no
 * longer authorize automatic answers, including after restart. Unknown sources and write failures must throw.
 * Called before final acceptance, independently of output collectors; retries must be idempotent. Cancellation
 * may leave revocation committed but must never authorize acceptance without a successful return.
 */
public fun interface StudioQuestionnaireAdmission {
    /** Revokes the saved human context of [source], without delivering or consuming the answer. */
    public suspend fun revoke(source: String)
}
