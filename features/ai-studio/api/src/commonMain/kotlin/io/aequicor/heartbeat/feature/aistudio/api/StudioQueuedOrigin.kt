package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Authenticated input boundary retained with the payload; a creator message never becomes human instruction. */
@Serializable
public sealed interface StudioQueuedOrigin {
    /** Existing rows without an origin were captured by the human composer. */
    @Serializable
    @SerialName("human")
    public data object Human : StudioQueuedOrigin

    /** Exact creator and native cause authenticated before queue persistence, independent of later native contexts. */
    @Serializable
    @SerialName("creator")
    public data class Creator(val creator: LogicalSessionId, val session: SessionRef, val request: RequestId) :
        StudioQueuedOrigin
}

/** Actual creator-message configuration captured at queue admission; profile security remains live. */
@Serializable
public data class StudioQueuedConfiguration(
    val target: EngineTarget,
    val reasoningEffort: String?,
    val reviewTarget: EngineTarget?,
)
