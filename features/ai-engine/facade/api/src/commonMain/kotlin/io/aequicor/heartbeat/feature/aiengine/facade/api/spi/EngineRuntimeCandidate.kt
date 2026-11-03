package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import kotlin.time.TimeMark

/**
 * Fixed input to one restricted acquisition. [launch] is the selected launch/configuration contract, not
 * permission to read its user or project files. The caller supplies an immutable snapshot; the candidate must
 * not reread global launch preferences or silently choose another installation during promotion.
 */
public data class EngineRuntimeCandidateRequest(val identity: RuntimeIdentity, val launch: LaunchContext) {
    override fun toString(): String = "EngineRuntimeCandidateRequest"
}

/**
 * One-use owner of a restricted process/client and the exact artifact/configuration contract it checked.
 * Acquisition and baseline use no real credentials, user/project configuration, outbound model prompts, hooks, tools,
 * login or user history writes. All resources remain owned even if acquisition is cancelled before returning.
 * Implementations must clean up such an interrupted acquisition themselves.
 * Synthetic protocol fixtures may exercise the actual SDK only through a socket-free, finite offline transport;
 * they do not establish ordinary session behavior, loaded-artifact provenance or admission by themselves.
 *
 * An external executable is pinned through a protected immutable copy or an already held process: hashing a
 * mutable path and reopening it after assessment does not bind the evidence to the launched artifact. Embedded
 * engines identify their actual build/dependency contract instead of inventing an executable fingerprint.
 * [close] is idempotent and always reaps owned resources, including after cancellation or failed promotion.
 */
public interface EngineRuntimeCandidate {
    /** The exact input retained by this candidate; no route, source or launch substitution is allowed. */
    public val request: EngineRuntimeCandidateRequest

    /** Independently observed facts; requirements belong to the registered factory, not this candidate. */
    public val evidence: EngineCompatibilityEvidence

    /**
     * Transfers an ordinary runtime only for the assessed [expected] artifact/contract. Reject a changed stamp
     * before any ordinary configuration access or session activity. The same absolute [deadline] covers baseline
     * and promotion. Promotion is allowed at most once; a failed or cancelled attempt cannot be retried on this
     * candidate. A failure before returning must release any runtime that has not been transferred.
     *
     * On success the returned runtime owns its resources independently: subsequent [close] releases only
     * remaining probe resources and must not close that runtime. The facade owns it immediately on return.
     */
    public suspend fun promote(expected: EngineCompatibilityStamp, deadline: TimeMark): EngineRuntimePromotion

    /** Releases all resources still owned by this candidate, without closing a transferred runtime. */
    public suspend fun close()
}

/** Actual identity attached to a transferred runtime, checked against the facade's assessed stamp. */
public data class EngineRuntimePromotion(val runtime: EngineRuntime, val stamp: EngineCompatibilityStamp) {
    override fun toString(): String = "EngineRuntimePromotion"
}
