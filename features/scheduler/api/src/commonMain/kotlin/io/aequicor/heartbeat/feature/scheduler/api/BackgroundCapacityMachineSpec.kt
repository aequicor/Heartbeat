package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Atomic admission of scheduler actions and workflow helper leases.
 * Durable running reservations restore before new admission.
 * Release scans the queue in arrival order, skipping requests blocked by their owner limit so another workflow
 * can progress. Existing eligible waiters always get released slots before new acquisitions.
 *
 * | From | Intent | Guard | To | Output |
 * |---|---|---|---|---|
 * | Ready | Restore | durable existing work | Ready(active union restored) | none |
 * | Ready | Acquire | duplicate id | stay | Rejected(Duplicate) |
 * | Ready | Acquire | new, both bounds hold | Ready(active+) | Granted |
 * | Ready | Acquire | helper, either bound full | Ready(queued+) | Queued |
 * | Ready | Acquire | scheduled, either bound full | stay | Rejected(ProfileLimit/SessionLimit) |
 * | Ready | Release | any id | Ready(remove id, drain eligible queue) | Granted if any |
 */
public val BackgroundCapacityMachineSpec: MachineSpec<
    BackgroundCapacityState,
    BackgroundCapacityIntent,
    BackgroundCapacityEffect,
    BackgroundCapacityOutput,
> = machineSpec(BackgroundCapacityMachineKey, BackgroundCapacityState.Ready()) {
    state<BackgroundCapacityState.Ready> {
        on<BackgroundCapacityIntent.Internal.Restore> {
            stay { state.restore(intent.reservations) }
        }
        on<BackgroundCapacityIntent.Public.Acquire>(guard = { state.contains(intent.request.id) }) {
            output { BackgroundCapacityOutput.Rejected(intent.request.id, BackgroundCapacityRejection.Duplicate) }
        }
        on<BackgroundCapacityIntent.Public.Acquire>(
            guard = { !state.contains(intent.request.id) && state.hasRoom(intent.request) },
        ) {
            stay { state.copy(active = state.active + intent.request) }
            output { BackgroundCapacityOutput.Granted(listOf(intent.request.id)) }
        }
        on<BackgroundCapacityIntent.Public.Acquire>(
            guard = {
                !state.contains(intent.request.id) && !state.hasRoom(intent.request) &&
                    intent.request.kind == BackgroundCapacityKind.Helper
            },
        ) {
            stay { state.copy(queued = state.queued + intent.request) }
            output { BackgroundCapacityOutput.Queued(intent.request.id) }
        }
        on<BackgroundCapacityIntent.Public.Acquire>(
            guard = {
                !state.contains(intent.request.id) && !state.hasRoom(intent.request) &&
                    intent.request.kind == BackgroundCapacityKind.Scheduled
            },
        ) {
            output {
                BackgroundCapacityOutput.Rejected(
                    intent.request.id,
                    if (state.active.size >= BackgroundCapacityLimits.PROFILE) {
                        BackgroundCapacityRejection.ProfileLimit
                    } else {
                        BackgroundCapacityRejection.SessionLimit
                    },
                )
            }
        }
        on<BackgroundCapacityIntent.Public.Release> {
            stay { state.release(intent.id) }
            output {
                val granted = state.release(intent.id).active.map { it.id } - state.active.map { it.id }.toSet()
                granted.takeIf { it.isNotEmpty() }?.let(BackgroundCapacityOutput::Granted)
            }
        }
    }
}

private fun BackgroundCapacityState.Ready.contains(id: ActionId): Boolean =
    active.any { it.id == id } || queued.any { it.id == id }

private fun BackgroundCapacityState.Ready.hasRoom(request: BackgroundReservation): Boolean =
    active.size < BackgroundCapacityLimits.PROFILE && when (request.kind) {
        BackgroundCapacityKind.Scheduled ->
            active.count { it.kind == BackgroundCapacityKind.Scheduled && it.parent == request.parent } <
                BackgroundCapacityLimits.SCHEDULED_PER_SESSION

        BackgroundCapacityKind.Helper ->
            active.count { it.kind == BackgroundCapacityKind.Helper && it.owner == request.owner } <
                BackgroundCapacityLimits.HELPERS_PER_OWNER
    }

private fun BackgroundCapacityState.Ready.release(id: ActionId): BackgroundCapacityState.Ready {
    var next = copy(active = active.filterNot { it.id == id }, queued = emptyList())
    queued.filterNot { it.id == id }.forEach { request ->
        next = if (next.hasRoom(request)) {
            next.copy(active = next.active + request)
        } else {
            next.copy(queued = next.queued + request)
        }
    }
    return next
}

private fun BackgroundCapacityState.Ready.restore(
    reservations: List<BackgroundReservation>,
): BackgroundCapacityState.Ready {
    val restored = reservations.associateBy { it.id }
    check(restored.size == reservations.size) { "Duplicate restored reservation" }
    check((active + queued).all { restored[it.id]?.let { old -> old == it } != false }) {
        "Restored reservation conflicts with current ownership"
    }
    val existing = active.map { it.id }.toSet()
    return copy(
        active = active + reservations.filterNot { it.id in existing },
        queued = queued.filterNot { it.id in restored },
    )
}
