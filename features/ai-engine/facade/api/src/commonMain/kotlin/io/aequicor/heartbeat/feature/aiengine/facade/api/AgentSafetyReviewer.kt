package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.StateFlow

/** Independent assessment in a fresh session without tools, workspace or inherited harnesses. */
public fun interface AgentSafetyReviewer {
    /**
     * Reviews untrusted [change] on [target] under static, trusted host [policy]. Consumers bind assessments to the
     * exact caller, model, policy, arguments and mutable revisions, and never review at execution. An unavailable,
     * ambiguous or incomplete assessment is never safe. Cancellation propagates.
     */
    public suspend fun review(target: EngineTarget, policy: String, change: String): AgentSafetyReview
}

/** The profile's automatic safety review load, for showing the user how busy the reviewer is. */
public interface AgentSafetyReviewMonitor {
    /** Current counts; updated whenever a review is queued, starts, ends or stops waiting for its session. */
    public val load: StateFlow<AgentSafetyReviewLoad>
}

/**
 * Reviews of one profile. [running] hold one of [capacity] slots with an open review session, [queued] wait for a
 * slot, [finishing] were stopped but still hold a slot until their session confirms termination. [abandoned] counts
 * reviews whose termination was never confirmed: their slot was released, their session keeps its tool restrictions.
 */
public data class AgentSafetyReviewLoad(
    val capacity: Int,
    val running: Int = 0,
    val queued: Int = 0,
    val finishing: Int = 0,
    val abandoned: Int = 0,
) {
    /** Nothing occupies or waits for the reviewer. */
    public val isIdle: Boolean get() = running == 0 && queued == 0 && finishing == 0
}

/** A complete strict verdict; reasons may contain private context and must not be logged. */
public data class AgentSafetyReview(val verdict: String, val reason: String) {
    public val isSafe: Boolean get() = verdict == "safe" && reason.isNotBlank()
    override fun toString(): String = "AgentSafetyReview(verdict=$verdict)"
}

/**
 * A contribution-owned, noninteractive safety decision for one hosted operation. This is independent of session
 * trust and durable authorization: a refusal cannot be overridden by either or by a user permission dialog.
 */
public sealed interface AgentToolAutomaticAuthorization {
    /** The independently reviewed snapshot. Exact equality and a nonblank binding are required by the host. */
    public data class Allowed(val approval: AgentToolApproval) : AgentToolAutomaticAuthorization {
        override fun toString(): String = "AgentToolAutomaticAuthorization.Allowed"
    }

    /** Risk, uncertainty, unavailable review or invalid input: return an error to the tool without asking. */
    public data class Denied(val reason: String) : AgentToolAutomaticAuthorization {
        override fun toString(): String = "AgentToolAutomaticAuthorization.Denied"
    }
}
