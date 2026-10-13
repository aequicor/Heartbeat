package io.aequicor.heartbeat.feature.aiengine.facade.api

/** Independent assessment in a fresh session without tools, workspace or inherited harnesses. */
public fun interface AgentSafetyReviewer {
    /**
     * Reviews untrusted [change] on [target] under static, trusted host [policy]. Consumers bind assessments to the
     * exact caller, model, policy, arguments and mutable revisions, and never review at execution. An unavailable,
     * ambiguous or incomplete assessment is never safe. Cancellation propagates.
     */
    public suspend fun review(target: EngineTarget, policy: String, change: String): AgentSafetyReview
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
