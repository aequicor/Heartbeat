package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.scheduler.api.HelperWorkBinding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Per-request provenance. Null in an older receipt remains unclassified until its work journal proves origin. */
@Serializable
internal sealed interface StudioAttemptPolicy {
    /** Independent human, creator or ordinary conversation continuation; never owned by the old helper lease. */
    @Serializable
    @SerialName("conversation")
    data object Conversation : StudioAttemptPolicy

    /** Owned graph execution; it is not a helper lease and never authorizes adoption by one. */
    @Serializable
    @SerialName("graph_work")
    data class GraphWork(val recoveryRoot: RequestId) : StudioAttemptPolicy {
        override fun toString(): String = "GraphWork(***)"
    }

    /** An explicit unknown service origin is not legacy null and cannot be promoted by a helper journal. */
    @Serializable
    @SerialName("unknown_service")
    data object UnknownService : StudioAttemptPolicy

    /** Immutable helper-specific policy; profile security remains live and is not replaced by a trust cap. */
    @Serializable
    @SerialName("helper_work")
    data class OwnedHelperWork(val binding: HelperWorkBinding, val model: HelperModelOptions) : StudioAttemptPolicy {
        override fun toString(): String = "OwnedHelperWork(***)"
    }
}

/** Safe work-only metadata, separate from the chat's currently active or most recent independent request. */
internal data class StudioWorkOwnership(val request: RequestId?, val isResolved: Boolean)

/** A later read may have a new model seed; an existing attempt always retains its original snapshot. */
internal fun StudioAttemptPolicy.hasSameOrigin(other: StudioAttemptPolicy?): Boolean = when (this) {
    StudioAttemptPolicy.Conversation -> other == StudioAttemptPolicy.Conversation

    StudioAttemptPolicy.UnknownService -> other == StudioAttemptPolicy.UnknownService

    is StudioAttemptPolicy.GraphWork -> other is StudioAttemptPolicy.GraphWork && recoveryRoot == other.recoveryRoot

    is StudioAttemptPolicy.OwnedHelperWork ->
        other is StudioAttemptPolicy.OwnedHelperWork && binding.owner == other.binding.owner && (
            binding.reservation == null || other.binding.reservation == null ||
                binding.reservation == other.binding.reservation
        )
}
