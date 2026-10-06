package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/** Bounds shared by scheduled background actions and workflow helpers in one open profile. */
public object BackgroundCapacityLimits {
    /** Commands, scheduled agents and helper leases share these slots. */
    public const val PROFILE: Int = 8

    /** Scheduled actions retain their existing per-session bound. */
    public const val SCHEDULED_PER_SESSION: Int = 3

    /** Concurrent helper leases for one workflow run. */
    public const val HELPERS_PER_OWNER: Int = 4
}

/** Which additional bound applies to a reservation. */
public enum class BackgroundCapacityKind {
    /** A scheduler command or agent; refused immediately when full. */
    Scheduled,

    /** A helper lease; waits until the profile and owner both have room. */
    Helper,
}

/**
 * One immutable reservation. [id] identifies this acquisition, while [owner] identifies the action or workflow run.
 * A recovered workflow uses new acquisition ids. Scheduled reservations require [parent].
 */
public data class BackgroundReservation(
    val id: ActionId,
    val owner: ActionId,
    val parent: SessionRef?,
    val kind: BackgroundCapacityKind,
) {
    init {
        require(kind != BackgroundCapacityKind.Scheduled || parent != null) { "Scheduled actions need a parent" }
    }
}

/** Ephemeral capacity; process restart begins empty and recovering workflows reacquire their leases. */
public sealed interface BackgroundCapacityState : MachineState {
    /** Active reservations and helper requests waiting in arrival order. No native work starts while queued. */
    public data class Ready(
        val active: List<BackgroundReservation> = emptyList(),
        val queued: List<BackgroundReservation> = emptyList(),
    ) : BackgroundCapacityState
}

/** Capacity commands; release is sent only after native work is settled or confirmed cancelled. */
public sealed interface BackgroundCapacityIntent : MachineIntent {
    /** Commands used by the profile admission service. */
    public sealed interface Public : BackgroundCapacityIntent {
        /** Grants, queues helpers, or refuses scheduled actions according to the same atomic bounds. */
        public data class Acquire(val request: BackgroundReservation) : Public

        /** Removes an active or queued acquisition and admits waiting helpers; repeated release is a no-op. */
        public data class Release(val id: ActionId) : Public
    }
}

/** This machine has no IO effects. */
public sealed interface BackgroundCapacityEffect : MachineEffect

/** Admission notifications; the active and queued state remains authoritative. */
public sealed interface BackgroundCapacityOutput : MachineOutput {
    /** An acquisition can now create or resume native work. */
    public data class Granted(val ids: List<ActionId>) : BackgroundCapacityOutput

    /** A helper must wait. */
    public data class Queued(val id: ActionId) : BackgroundCapacityOutput

    /** The acquisition was refused without changing capacity. */
    public data class Rejected(val id: ActionId, val reason: BackgroundCapacityRejection) : BackgroundCapacityOutput
}

/** Why an immediate acquisition could not proceed. */
public enum class BackgroundCapacityRejection {
    /** This acquisition id already exists. */
    Duplicate,

    /** All profile slots are reserved. */
    ProfileLimit,

    /** The initiating session already has the maximum scheduled actions. */
    SessionLimit,
}

/** Profile-wide admission; callers use the scheduler service, not a second independent semaphore. */
public object BackgroundCapacityMachineKey : MachineKey<
    BackgroundCapacityState,
    BackgroundCapacityIntent,
    BackgroundCapacityIntent.Public,
    BackgroundCapacityEffect,
    BackgroundCapacityOutput,
> {
    override val name: String = "background_capacity"
}
