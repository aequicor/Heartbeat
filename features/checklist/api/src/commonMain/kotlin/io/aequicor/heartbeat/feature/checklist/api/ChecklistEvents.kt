package io.aequicor.heartbeat.feature.checklist.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import kotlinx.serialization.Serializable

/** Compact durable event: content stays in the checklist journal and can be read with checklist_get. */
@Serializable
public data class ChecklistEvent(
    val id: String,
    val revision: Long,
    val session: SessionRef,
    val request: RequestId,
    val turn: String,
    val callId: String?,
    val mode: ChecklistCompletionMode,
    val status: ChecklistStatus,
) {
    /** Stable identity across retries, independent of the bus timestamp. */
    public val eventId: String get() = "${id}_$revision"
}

/** Acknowledgement emitted only after the studio has durably applied an event. */
@Serializable
public data class ChecklistAcknowledgement(val eventId: String)

/** Keys on the scheduler's single profile bus. Only Host-origin events are consumed by this feature. */
public object ChecklistEvents {
    /** Host workflow identity carried by a checklist continuation wake. */
    public const val OWNER: String = "checklist"

    /** Requests replay of session generations after checklist startup or re-enabling. */
    public val Synchronize: EventKey = EventKeys.custom("checklist.synchronize")

    /** The studio has durably applied a card event. */
    public val Acknowledged: EventKey = EventKeys.custom("checklist.acknowledged")

    /** An event for one card, also the exact wake condition for its completion. */
    public fun changed(id: String, status: ChecklistStatus): EventKey =
        EventKeys.custom("checklist.$id.${status.name.lowercase()}")
}

/** Snapshot for the durable event queue. */
public fun Checklist.event(): ChecklistEvent =
    ChecklistEvent(id, revision, session, request, historyTurn.value, callId, mode, status)
