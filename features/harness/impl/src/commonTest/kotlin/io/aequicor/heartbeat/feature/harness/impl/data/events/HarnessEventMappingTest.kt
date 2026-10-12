package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.harness.api.event.EngineEvent
import io.aequicor.heartbeat.feature.harness.api.event.SchedulerEvent
import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.WakeRejection
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HarnessEventMappingTest {
    @Test
    fun `network mapping requires exact platform origin and keeps forged payload untrusted`() {
        val at = Instant.fromEpochMilliseconds(1)
        val forged = BusEvent(EventKeys.NetworkAvailable, EventOrigin.Host, at, "private payload")
        assertNull(forged.networkEvent())
        assertEquals(forged.payload, forged.harnessEvent().payload?.text)
        assertFalse(forged.harnessEvent().toString().contains("private payload"))
        assertEquals(SystemEvent.NetworkChanged(true, at), forged.copy(origin = EventOrigin.System).networkEvent())
        assertEquals(
            SystemEvent.NetworkChanged(false, at),
            forged.copy(key = EventKeys.NetworkLost, origin = EventOrigin.System).networkEvent(),
        )
        assertNull(forged.copy(key = EventKeys.custom("network.available"), origin = EventOrigin.System).networkEvent())
    }

    @Test
    fun `scheduler projections carry identities and outcomes without private wake request`() {
        val at = Instant.fromEpochMilliseconds(1)
        val id = WakeId("wake")
        val wake = ScheduledWake(
            WakeRequest(
                id,
                dispatchSession,
                null,
                WakeCondition(deadline = at),
                "private wake note",
                WakeOrigin.Feature("private owner", "private label"),
            ),
            at,
        )
        val reason = WakeReason.Deadline(at)
        val projections = listOf(
            SchedulerOutput.Scheduled(wake).harnessEvent(at),
            SchedulerOutput.Woke(wake, reason).harnessEvent(at),
            SchedulerOutput.DeliveryFailed(listOf(wake), WakeFailure.Engine).harnessEvent(at),
            SchedulerOutput.Cancelled(listOf(id)).harnessEvent(at),
            SchedulerOutput.Rejected(id, WakeRejection.Duplicate).harnessEvent(at),
        )
        assertEquals(
            listOf(
                SchedulerEvent.WakeScheduled(id, dispatchSession, at),
                SchedulerEvent.Woke(id, dispatchSession, reason, at),
                SchedulerEvent.WakeFailed(listOf(id), WakeFailure.Engine, at),
                SchedulerEvent.WakesCancelled(listOf(id), at),
                SchedulerEvent.WakeRejected(id, WakeRejection.Duplicate, at),
            ),
            projections,
        )
        assertTrue(projections.all { !it.toString().contains("private") })
        assertNull(SchedulerOutput.Deferred(id).harnessEvent(at))
    }

    @Test
    fun `cached engine snapshots emit only changed availability without observation duplicates`() {
        val at = Instant.fromEpochMilliseconds(1)
        val descriptor = EngineDescriptor(
            EngineId("engine"),
            "private title",
            EngineFamily.BuiltIn,
            emptySet(),
            AiEngines,
        )
        val initial = EngineInfo(descriptor, EngineAvailability.Unknown, emptyList())
        val changes = HarnessEngineChanges()
        assertEquals(emptyList(), changes.engines(listOf(initial), at))
        val available = initial.copy(availability = EngineAvailability.Available)
        assertEquals(
            listOf(EngineEvent.AvailabilityChanged(descriptor.id, EngineAvailability.Available, at)),
            changes.engines(listOf(available), at),
        )
        assertEquals(emptyList(), changes.engines(listOf(available), at))
        assertEquals(emptyList(), changes.engines(listOf(available.copy(observation = Observation(at, false))), at))
        assertEquals(emptyList(), changes.engines(emptyList(), at))
    }

    @Test
    fun `binding enable and credential changes expose only ids and removal exposes empty set`() {
        val at = Instant.fromEpochMilliseconds(1)
        val binding = EngineBinding(EngineBindingId("binding"), EngineId("engine"), AuthSourceId("private_credential"))
        val changes = HarnessEngineChanges()
        assertEquals(emptyList(), changes.connections(listOf(binding), at))
        assertEquals(emptyList(), changes.connections(listOf(binding), at))
        val disabled = binding.copy(isEnabled = false)
        val expected = listOf(EngineEvent.ConnectionsChanged(binding.engine, setOf(binding.id), at))
        assertEquals(expected, changes.connections(listOf(disabled), at))
        val changed = changes.connections(listOf(disabled.copy(authSource = AuthSourceId("another_private"))), at)
        assertEquals(expected, changed)
        assertFalse(changed.toString().contains("private"))
        assertEquals(
            listOf(EngineEvent.ConnectionsChanged(binding.engine, emptySet(), at)),
            changes.connections(emptyList(), at),
        )
    }
}
