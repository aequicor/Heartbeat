package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.Serializable

/** Host lifecycle events shared by features on SchedulerBus, independent of automatic wake availability. */
public object SchedulerEvents {
    /** A host persisted a new execution generation before native submission. */
    public val RunStarted: EventKey = EventKeys.custom("scheduler.run_started")

    /** A wake was accepted or delivery failed; inspect WakeResultEvent for its identity. */
    public val WakeResult: EventKey = EventKeys.custom("scheduler.wake_result")
}

/** A persisted execution identity. Replaying it restores consumers after startup. */
@Serializable
public data class RunStartedEvent(val session: SessionRef, val request: RequestId, val revision: Long = 0)

/** The scheduler's delivery result; true means native acceptance, not successful execution. */
@Serializable
public data class WakeResultEvent(
    val id: WakeId,
    val isSuccessful: Boolean,
    /** Admission paused before submission. The same wake can be retried after its next event. */
    val isDeferred: Boolean = false,
)
