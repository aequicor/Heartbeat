package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId

/** Host-only execution provenance, independent of the logical chat role and never accepted from model arguments. */
internal sealed interface StudioTurnOrigin {
    data object Conversation : StudioTurnOrigin

    /** The authenticated lease gate supplies the immutable binding and model snapshot. */
    data object HelperWork : StudioTurnOrigin

    /** Taken from the graph journal's retained attempt, after its chat and recovery root were validated. */
    data class GraphWork(val recoveryRoot: RequestId) : StudioTurnOrigin {
        override fun toString(): String = "GraphWork(***)"
    }

    /** A service request with no proven independent-dialogue or work ancestry. */
    data object UnknownService : StudioTurnOrigin
}

/** Independent delivery cannot manufacture a helper lease; its gate is the only proof of that origin. */
internal fun StudioTurnOrigin.deliveryPolicy(): StudioAttemptPolicy = when (this) {
    StudioTurnOrigin.Conversation -> StudioAttemptPolicy.Conversation
    StudioTurnOrigin.HelperWork -> error("Helper work requires an authenticated lease submission")
    is StudioTurnOrigin.GraphWork -> StudioAttemptPolicy.GraphWork(recoveryRoot)
    StudioTurnOrigin.UnknownService -> StudioAttemptPolicy.UnknownService
}

/** Legacy undeclared callers keep the gate's proof; an explicit producer cannot contradict that proof. */
internal fun StudioTurnOrigin.accepts(policy: StudioAttemptPolicy?): Boolean = when (this) {
    StudioTurnOrigin.Conversation -> policy == StudioAttemptPolicy.Conversation
    StudioTurnOrigin.HelperWork -> policy is StudioAttemptPolicy.OwnedHelperWork
    is StudioTurnOrigin.GraphWork -> policy is StudioAttemptPolicy.GraphWork && policy.recoveryRoot == recoveryRoot
    StudioTurnOrigin.UnknownService -> true
}
