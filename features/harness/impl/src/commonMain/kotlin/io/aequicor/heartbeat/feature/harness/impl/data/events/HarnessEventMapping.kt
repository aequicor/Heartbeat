package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.harness.api.event.EngineEvent
import io.aequicor.heartbeat.feature.harness.api.event.HarnessBusEvent
import io.aequicor.heartbeat.feature.harness.api.event.SchedulerEvent
import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import io.aequicor.heartbeat.feature.harness.api.event.UntrustedEventPayload
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import kotlin.time.Instant

/** Copies the intentionally untrusted bus text without interpreting it as an execution origin. */
internal fun BusEvent.harnessEvent(): HarnessBusEvent =
    HarnessBusEvent(key, origin, at, payload?.let(::UntrustedEventPayload))

/** Only exact host system keys establish network observations; a named user signal cannot impersonate them. */
internal fun BusEvent.networkEvent(): SystemEvent.NetworkChanged? {
    if (origin != EventOrigin.System) return null
    return when (key) {
        EventKeys.NetworkAvailable -> SystemEvent.NetworkChanged(true, at)
        EventKeys.NetworkLost -> SystemEvent.NetworkChanged(false, at)
        else -> null
    }
}

/** Explicit projection prevents scheduler wake notes and full requests from crossing the script API. */
internal fun SchedulerOutput.harnessEvent(at: Instant): SchedulerEvent? = when (this) {
    is SchedulerOutput.Scheduled -> SchedulerEvent.WakeScheduled(wake.id, wake.session, at)
    is SchedulerOutput.Woke -> SchedulerEvent.Woke(wake.id, wake.session, reason, at)
    is SchedulerOutput.DeliveryFailed -> SchedulerEvent.WakeFailed(wakes.map { it.id }, failure, at)
    is SchedulerOutput.Cancelled -> SchedulerEvent.WakesCancelled(ids.toList(), at)
    is SchedulerOutput.Rejected -> SchedulerEvent.WakeRejected(id, rejection, at)
    is SchedulerOutput.Deferred -> null
}

/** Diffs cached catalog snapshots only; initial snapshots establish a baseline and never trigger engine probes. */
internal class HarnessEngineChanges {
    private var availability: Map<EngineId, EngineAvailability>? = null
    private var bindings: Map<EngineId, Set<EngineBinding>>? = null
    private val log = Log.tag("HarnessRuntime")

    @HighFrequency
    fun engines(current: List<EngineInfo>, at: Instant): List<EngineEvent.AvailabilityChanged> {
        log.v { "project cached engine availability" }
        val next = current.associate { it.descriptor.id to it.availability }
        val previous = availability
        availability = next
        if (previous == null) return emptyList()
        // Disappearance has no corresponding public availability value; do not invent a failure.
        return next.filter { (id, value) -> previous[id] != value }.map { (id, value) ->
            EngineEvent.AvailabilityChanged(id, value, at)
        }
    }

    @HighFrequency
    fun connections(current: List<EngineBinding>, at: Instant): List<EngineEvent.ConnectionsChanged> {
        log.v { "project cached engine connections" }
        val next = current.groupBy { it.engine }.mapValues { it.value.toSet() }
        val previous = bindings
        bindings = next
        if (previous == null) return emptyList()
        return (previous.keys + next.keys).filter { previous[it] != next[it] }.map { id ->
            EngineEvent.ConnectionsChanged(id, next[id].orEmpty().mapTo(linkedSetOf()) { it.id }, at)
        }
    }
}
