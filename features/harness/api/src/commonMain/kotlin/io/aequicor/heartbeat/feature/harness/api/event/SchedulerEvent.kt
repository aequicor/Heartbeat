package io.aequicor.heartbeat.feature.harness.api.event

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.WakeRejection
import kotlin.time.Instant

/** Scheduler output projections intentionally omit wake notes and full requests. */
public sealed class SchedulerEvent : HarnessEvent() {
    /** A pending wake was accepted by the scheduler, not yet delivered to its session. */
    public data class WakeScheduled(val id: WakeId, val session: SessionRef, override val at: Instant) :
        SchedulerEvent()

    /** A session accepted its wake prompt. Any event payload inside [reason] remains untrusted data. */
    public data class Woke(val id: WakeId, val session: SessionRef, val reason: WakeReason, override val at: Instant) :
        SchedulerEvent()

    /** These due wakes were dropped after delivery failed; this is not a workflow terminal receipt. */
    public data class WakeFailed(val ids: List<WakeId>, val failure: WakeFailure, override val at: Instant) :
        SchedulerEvent()

    /** Pending wakes were cancelled; accepted native turns are not claimed to have stopped. */
    public data class WakesCancelled(val ids: List<WakeId>, override val at: Instant) : SchedulerEvent()

    /** A new wake was rejected before being scheduled. */
    public data class WakeRejected(val id: WakeId, val rejection: WakeRejection, override val at: Instant) :
        SchedulerEvent()
}
