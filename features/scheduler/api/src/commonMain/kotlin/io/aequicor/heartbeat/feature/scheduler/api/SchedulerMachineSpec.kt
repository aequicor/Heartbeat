package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlin.time.Instant

/**
 * Puts sessions to sleep and wakes them on bus events or deadlines. Matching is pure: the impl feeds every bus event
 * as [SchedulerIntent.Internal.Observed] and a [SchedulerIntent.Internal.Tick] when the earliest deadline passes.
 * Delivery is at least once: a wake leaves the schedule only after its delivery settled, so a wake interrupted by a
 * restart is due again (a past deadline on the first tick, an event on its next occurrence).
 *
 * | From | Intent | Guard | To | Effect / output |
 * |---|---|---|---|---|
 * | Loading | Start | — | stay | Load |
 * | Loading | Loaded | — | Ready(wakes) | — |
 * | Loading | LoadFailed | — | Ready() | — |
 * | Loading | any Public, Observed, Tick, Delivered, DeliveryFailed | — | ignored | — |
 * | Ready | Schedule | id new, limits hold, deadline within the horizon | Ready(+wake) | Persist; Scheduled |
 * | Ready | Schedule | otherwise | stay | Rejected |
 * | Ready | Cancel | pending, not delivering, same session if given | Ready(−wake) | Persist; Cancelled |
 * | Ready | CancelSession | the session has a pending wake not delivering | Ready(−wakes) | Persist; Cancelled |
 * | Ready | Observed | ≥1 pending wake matches the event | Ready(delivering+) | Deliver |
 * | Ready | Tick | ≥1 pending deadline ≤ now | Ready(delivering+) | Deliver |
 * | Ready | Delivered | the wake is delivering | Ready(−wake) | Persist; Woke |
 * | Ready | DeliveryFailed | ≥1 of the wakes is delivering | Ready(−wakes) | Persist; DeliveryFailed |
 * | Ready | Observed / Tick without a match | — | ignored | — |
 *
 * Effect failures: Load → LoadFailed; Deliver → DeliveryFailed(Unknown) for its wakes; Persist is only logged.
 */
public val SchedulerMachineSpec: MachineSpec<SchedulerState, SchedulerIntent, SchedulerEffect, SchedulerOutput> =
    machineSpec(SchedulerMachineKey, SchedulerState.Loading) {
        state<SchedulerState.Loading> {
            on<SchedulerIntent.Internal.Start> { effect { SchedulerEffect.Load } }
            on<SchedulerIntent.Internal.Loaded> {
                goto<SchedulerState.Ready> { SchedulerState.Ready(intent.wakes) }
            }
            on<SchedulerIntent.Internal.LoadFailed> {
                goto<SchedulerState.Ready> { SchedulerState.Ready() }
            }
        }
        state<SchedulerState.Ready> {
            on<SchedulerIntent.Public.Schedule>(guard = { state.rejection(intent.request, intent.at) == null }) {
                stay { state.changed(state.wakes + ScheduledWake(intent.request, intent.at)) }
                effect { state.persist(state.wakes + ScheduledWake(intent.request, intent.at)) }
                output { SchedulerOutput.Scheduled(ScheduledWake(intent.request, intent.at)) }
            }
            on<SchedulerIntent.Public.Schedule>(guard = { state.rejection(intent.request, intent.at) != null }) {
                // The guard proved a rejection; the fallback is unreachable.
                output {
                    SchedulerOutput.Rejected(
                        intent.request.id,
                        state.rejection(intent.request, intent.at) ?: WakeRejection.Duplicate,
                    )
                }
            }
            on<SchedulerIntent.Public.Cancel>(guard = { state.cancellable { it.matchesCancel(intent) }.isNotEmpty() }) {
                stay { state.changed(state.wakes - state.cancellable { it.matchesCancel(intent) }.toSet()) }
                effect { state.persist(state.wakes - state.cancellable { it.matchesCancel(intent) }.toSet()) }
                output { SchedulerOutput.Cancelled(listOf(intent.id)) }
            }
            on<SchedulerIntent.Public.CancelSession>(
                guard = { state.cancellable { it.session == intent.session }.isNotEmpty() },
            ) {
                stay { state.changed(state.wakes - state.cancellable { it.session == intent.session }.toSet()) }
                effect { state.persist(state.wakes - state.cancellable { it.session == intent.session }.toSet()) }
                output { SchedulerOutput.Cancelled(state.cancellable { it.session == intent.session }.map { it.id }) }
            }
            on<SchedulerIntent.Internal.Observed>(
                guard = { state.cancellable { it.matches(intent.event) }.isNotEmpty() },
            ) {
                stay { state.startDelivering(state.cancellable { it.matches(intent.event) }) }
                effect {
                    SchedulerEffect.Deliver(
                        state.cancellable { it.matches(intent.event) }
                            .map { WakeDelivery(it, WakeReason.Event(intent.event)) },
                    )
                }
            }
            on<SchedulerIntent.Internal.Tick>(guard = { state.cancellable { it.isDue(intent.now) }.isNotEmpty() }) {
                stay { state.startDelivering(state.cancellable { it.isDue(intent.now) }) }
                effect { SchedulerEffect.Deliver(state.cancellable { it.isDue(intent.now) }.map(::deadlineDelivery)) }
            }
            on<SchedulerIntent.Internal.Delivered>(guard = { intent.id in state.delivering }) {
                stay { state.settled(setOf(intent.id)) }
                effect { state.settled(setOf(intent.id)).let { SchedulerEffect.Persist(it.wakes, it.revision) } }
                output {
                    state.wakes.firstOrNull { it.id == intent.id }?.let {
                        SchedulerOutput.Woke(
                            it,
                            intent.reason,
                        )
                    }
                }
            }
            on<SchedulerIntent.Internal.DeliveryFailed>(guard = { intent.ids.any { it in state.delivering } }) {
                stay { state.settled(intent.ids.toSet()) }
                effect { state.settled(intent.ids.toSet()).let { SchedulerEffect.Persist(it.wakes, it.revision) } }
                output {
                    SchedulerOutput.DeliveryFailed(
                        state.wakes.filter { it.id in intent.ids && it.id in state.delivering },
                        intent.failure,
                    )
                }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                SchedulerEffect.Load -> SchedulerIntent.Internal.LoadFailed

                is SchedulerEffect.Persist -> null

                is SchedulerEffect.Deliver ->
                    SchedulerIntent.Internal.DeliveryFailed(effect.deliveries.map { it.wake.id }, WakeFailure.Unknown)
            }
        }
    }

/** Why [request] cannot be scheduled at [now], or null when it can. */
private fun SchedulerState.Ready.rejection(request: WakeRequest, now: Instant): WakeRejection? {
    val deadline = request.condition.deadline
    return when {
        wakes.any { it.id == request.id } -> WakeRejection.Duplicate
        wakes.count { it.session == request.session } >= SchedulerLimits.MAX_PER_SESSION -> WakeRejection.SessionLimit
        wakes.size >= SchedulerLimits.MAX_PER_PROFILE -> WakeRejection.ProfileLimit
        deadline != null && deadline - now > SchedulerLimits.HORIZON -> WakeRejection.TooFar
        else -> null
    }
}

/** Pending wakes that are not being delivered and satisfy [predicate]. */
private inline fun SchedulerState.Ready.cancellable(predicate: (ScheduledWake) -> Boolean): List<ScheduledWake> =
    wakes.filter { it.id !in delivering && predicate(it) }

private fun ScheduledWake.matchesCancel(cancel: SchedulerIntent.Public.Cancel): Boolean =
    id == cancel.id && (cancel.session == null || cancel.session == session)

private fun SchedulerState.Ready.changed(next: List<ScheduledWake>): SchedulerState.Ready =
    copy(wakes = next, revision = revision + 1)

private fun SchedulerState.Ready.persist(next: List<ScheduledWake>): SchedulerEffect.Persist =
    SchedulerEffect.Persist(next, revision + 1)

private fun SchedulerState.Ready.startDelivering(due: List<ScheduledWake>): SchedulerState.Ready =
    copy(delivering = delivering + due.map { it.id })

/** Drops the delivering wakes among [ids]. */
private fun SchedulerState.Ready.settled(ids: Set<WakeId>): SchedulerState.Ready {
    val done = ids intersect delivering
    return copy(wakes = wakes.filterNot { it.id in done }, delivering = delivering - done, revision = revision + 1)
}

private fun deadlineDelivery(wake: ScheduledWake): WakeDelivery =
    WakeDelivery(wake, WakeReason.Deadline(checkNotNull(wake.request.condition.deadline)))
