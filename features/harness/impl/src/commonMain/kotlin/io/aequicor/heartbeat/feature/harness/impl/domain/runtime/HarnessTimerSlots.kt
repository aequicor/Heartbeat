package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.ItemId
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Profile-local timer quota. A candidate owns private slots until the runtime commits publication. One CAS
 * replaces its item's quota together with checking all other published items of that harness. Dynamic
 * registration and disposal use the same state, so they cannot race that commit past [HarnessLimits.TIMERS].
 * Quota is released by disposal, replacement or close, never by a reversible admission snapshot. A retiring
 * generation conservatively keeps its slots until actual execution drains and its context closes.
 */
internal class HarnessTimerSlots {
    private val state = MutableStateFlow(TimerSlotsState())
    private val log = Log.tag("HarnessTimers")

    @HighFrequency
    fun stage(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessTimerOwner {
        log.v { "stage private timer owner" }
        val owner = HarnessTimerOwner(request.harness.id, request.item.id, access)
        while (true) {
            val before = state.value
            val after = before.copy(owners = before.owners + (owner to TimerAllocation()))
            if (state.compareAndSet(before, after)) return owner
        }
    }

    /** Null rejects an unavailable generation or a full quota without allocating a slot. */
    @HighFrequency
    fun reserve(owner: HarnessTimerOwner): Long? {
        log.v { "reserve timer quota" }
        while (true) {
            val before = state.value
            val after = before.reserving(owner) ?: return null
            if (state.compareAndSet(before, after)) return before.nextId
        }
    }

    /** Called only by the runtime publication transaction, after the instance phase CAS succeeded. */
    @HighFrequency
    fun publish(owner: HarnessTimerOwner): Boolean {
        log.v { "commit timer quota" }
        while (true) {
            val before = state.value
            val after = before.publishing(owner) ?: return false
            if (state.compareAndSet(before, after)) return true
        }
    }

    @HighFrequency
    fun release(owner: HarnessTimerOwner, id: Long) {
        log.v { "release timer quota" }
        while (true) {
            val before = state.value
            val allocation = before.owners[owner]?.takeIf { id in it.slots } ?: return
            val after = before.copy(owners = before.owners + (owner to allocation.copy(slots = allocation.slots - id)))
            if (state.compareAndSet(before, after)) return
        }
    }

    /** Old generation cleanup cannot remove its replacement's current pointer or registrations. */
    @HighFrequency
    fun close(owner: HarnessTimerOwner) {
        log.v { "close timer owner" }
        while (true) {
            val before = state.value
            if (owner !in before.owners) return
            val current = if (before.current[owner.key] === owner) before.current - owner.key else before.current
            val after = before.copy(owners = before.owners - owner, current = current)
            if (state.compareAndSet(before, after)) return
        }
    }
}

/** Identity token, never equal to another activation even when their persisted keys are equal. */
internal class HarnessTimerOwner(val harness: HarnessId, item: ItemId, val access: HarnessInstanceAccess) {
    val key = HarnessTimerKey(harness, item)
}

internal data class HarnessTimerKey(val harness: HarnessId, val item: ItemId)

private data class TimerAllocation(val slots: Set<Long> = emptySet(), val isPublished: Boolean = false)

private data class TimerSlotsState(
    val owners: Map<HarnessTimerOwner, TimerAllocation> = emptyMap(),
    val current: Map<HarnessTimerKey, HarnessTimerOwner> = emptyMap(),
    val nextId: Long = 0,
) {
    fun reserving(owner: HarnessTimerOwner): TimerSlotsState? {
        val allocation = owners[owner] ?: return null
        if (!owner.access.isRegistrationAllowed) return null
        if (allocation.isPublished && current[owner.key] !== owner) return null
        val count = if (allocation.isPublished) activeCount(owner.harness) else allocation.slots.size
        if (count >= HarnessLimits.TIMERS) return null
        return copy(
            nextId = nextId + 1,
            owners = owners + (owner to allocation.copy(slots = allocation.slots + nextId)),
        )
    }

    fun publishing(owner: HarnessTimerOwner): TimerSlotsState? {
        val allocation = owners[owner] ?: return null
        if (allocation.isPublished) return takeIf { current[owner.key] === owner }
        val count = activeCount(owner.harness, excluding = owner.key) + allocation.slots.size
        if (count > HarnessLimits.TIMERS) return null
        return copy(
            current = current + (owner.key to owner),
            owners = owners + (owner to allocation.copy(isPublished = true)),
        )
    }

    private fun activeCount(harness: HarnessId, excluding: HarnessTimerKey? = null): Int =
        current.entries.sumOf { (key, owner) ->
            if (key.harness == harness && key != excluding) owners[owner]?.slots?.size ?: 0 else 0
        }
}
