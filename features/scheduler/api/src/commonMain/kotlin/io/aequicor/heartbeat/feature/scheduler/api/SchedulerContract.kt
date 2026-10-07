package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlin.time.Instant

/** Pending wakes of the profile. Durable data lives in the profile store and is reloaded by [SchedulerEffect.Load]. */
public sealed interface SchedulerState : MachineState {
    /** Reading stored wakes; requests are not accepted yet. */
    public data object Loading : SchedulerState

    /**
     * Accepting requests. [delivering] are due wakes whose prompt is being delivered; they leave [wakes] only when the
     * delivery settles. [revision] grows with every change of [wakes] and orders persistence.
     */
    public data class Ready(
        val wakes: List<ScheduledWake> = emptyList(),
        val delivering: Set<WakeId> = emptySet(),
        val revision: Long = 0,
    ) : SchedulerState
}

/**
 * Whether [event] wakes a pending wake that is not being delivered. Drivers use it to feed only awaited events to the
 * machine (an ignored intent is logged at WARN); the spec matches with the same rule.
 */
public fun SchedulerState.Ready.isAwaited(event: BusEvent): Boolean =
    wakes.any { it.id !in delivering && it.matches(event) }

/** Commands from other features and agents, results of effects and inputs of the bus and the timer. */
public sealed interface SchedulerIntent : MachineIntent {
    /** Commands sent through [SchedulerMachineKey]. */
    public sealed interface Public : SchedulerIntent {
        /**
         * Puts a session to sleep until [request]'s condition holds; [at] is the caller's current time (the spec is
         * pure). Answered by [SchedulerOutput.Scheduled] or [SchedulerOutput.Rejected]; ignored while loading.
         */
        public data class Schedule(val request: WakeRequest, val at: Instant) : Public

        /**
         * Cancels a pending wake; [session] and [expectedRequest], when supplied, must still match at transition
         * time. An immutable expected request prevents a delayed cancellation from removing a reused id.
         */
        public data class Cancel(
            val id: WakeId,
            val session: SessionRef? = null,
            /** Trusted caller ancestry, retained together with the removed wake's origin. Never tool payload. */
            val cause: EventOrigin? = null,
            val expectedRequest: WakeRequest? = null,
        ) : Public

        /** Cancels every pending wake of [session] (its chat was deleted or it no longer wants to sleep). */
        public data class CancelSession(val session: SessionRef) : Public

        /**
         * Cancels pending wakes belonging to [feature]. Delivering wakes are settled by their live owner admission:
         * this command never claims to revoke an already submitted native turn. Accepted as a no-op when none match;
         * ignored while Loading, so callers must wait for Ready before sending it.
         */
        public data class CancelOwned(val feature: String) : Public
    }

    /** Effect results, bus events and timer ticks. */
    public sealed interface Internal : SchedulerIntent {
        /** The profile started the scheduler; stored wakes are read. */
        public data object Start : Internal

        /** Stored wakes were read. */
        public data class Loaded(val wakes: List<ScheduledWake>) : Internal

        /** Stored wakes could not be read; the schedule starts empty. */
        public data object LoadFailed : Internal

        /** An event was published on the bus. */
        public data class Observed(val event: BusEvent) : Internal

        /** The timer reached the earliest deadline; [now] is the current time. */
        public data class Tick(val now: Instant) : Internal

        /** The session accepted the wake prompt of [id]. */
        public data class Delivered(val id: WakeId, val reason: WakeReason) : Internal

        /** Host paused admission without submitting; retain the wait for event replay. */
        public data class Deferred(val id: WakeId) : Internal

        /** The wake prompts of [ids] could not be delivered. */
        public data class DeliveryFailed(
            val ids: List<WakeId>,
            val failure: WakeFailure,
            /** Trigger origins retained even when admission failed before the target request was registered. */
            val origins: List<EventOrigin> = emptyList(),
        ) : Internal
    }
}

/** IO commands executed in the feature impl. */
public sealed interface SchedulerEffect : MachineEffect {
    /** Reads stored wakes. */
    public data object Load : SchedulerEffect

    /** Stores [wakes]; a write older than the last stored [revision] is skipped. */
    public data class Persist(val wakes: List<ScheduledWake>, val revision: Long) : SchedulerEffect

    /** Wakes each session of [deliveries]; every delivery settles with Delivered or DeliveryFailed. */
    public data class Deliver(val deliveries: List<WakeDelivery>) : SchedulerEffect
}

/** One due wake and why it is due. */
public data class WakeDelivery(val wake: ScheduledWake, val reason: WakeReason)

/** Transient notifications; [SchedulerState.Ready.wakes] stays authoritative. */
public sealed interface SchedulerOutput : MachineOutput {
    /** Host paused admission without submitting; wake remains scheduled. */
    public data class Deferred(val id: WakeId) : SchedulerOutput

    /** [wake] is pending. */
    public data class Scheduled(val wake: ScheduledWake) : SchedulerOutput

    /** The request [id] was not scheduled. */
    public data class Rejected(
        val id: WakeId,
        val rejection: WakeRejection,
        /** Host ancestry retained even when no pending wake was created. Contains no note. */
        val origin: EventOrigin.Feature? = null,
        /** Initiating request retained even when no wake row is created. */
        val initiator: RequestInitiator? = null,
        /** Exact attempted target, including restrictions persisted by an earlier delivery attempt. */
        val deliveryRequest: RequestInitiator? = null,
    ) : SchedulerOutput

    /** Pending wakes [ids] were cancelled. */
    public data class Cancelled(
        val ids: List<WakeId>,
        /** Immutable owners, initiators, target delivery requests and cancellation cause, without private notes. */
        val origins: List<EventOrigin> = emptyList(),
    ) : SchedulerOutput

    /** [wake]'s session accepted its wake prompt. */
    public data class Woke(val wake: ScheduledWake, val reason: WakeReason) : SchedulerOutput

    /** [wakes] were due but could not be delivered and were dropped. */
    public data class DeliveryFailed(
        val wakes: List<ScheduledWake>,
        val failure: WakeFailure,
        /** Trusted trigger ancestry only; no event payload or wake note. */
        val origins: List<EventOrigin> = emptyList(),
    ) : SchedulerOutput
}

/**
 * Profile-scoped scheduler machine. It is started with the profile, so `MachineRegistry.send` reaches it while the
 * profile is open; requests sent before stored wakes are loaded are ignored.
 */
public object SchedulerMachineKey :
    MachineKey<SchedulerState, SchedulerIntent, SchedulerIntent.Public, SchedulerEffect, SchedulerOutput> {
    override val name: String = "scheduler"
}
