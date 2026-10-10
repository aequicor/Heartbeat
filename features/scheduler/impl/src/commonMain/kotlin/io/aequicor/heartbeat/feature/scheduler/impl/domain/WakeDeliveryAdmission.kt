package io.aequicor.heartbeat.feature.scheduler.impl.domain

import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledEventOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job

/** Both independent authorities must allow; either live refusal remains sticky through host rechecks. */
internal class WakeDeliveryAdmission private constructor(private val gates: List<OwnedWakeAdmission>) {
    val failure: WakeFailure get() = if (gates.any { it.failure == WakeFailure.OwnerUnavailable }) {
        WakeFailure.OwnerUnavailable
    } else {
        WakeFailure.OwnerRejected
    }

    val decisions: Flow<ScheduledWakeAdmission> = flow {
        coroutineScope {
            val owner = coroutineContext.job
            val sources = gates.map { gate ->
                gate.decisions.onCompletion { cause ->
                    // combine otherwise treats a self-cancelled source as a completed child and may wait forever.
                    if (cause is CancellationException && currentCoroutineContext().isActive) owner.cancel(cause)
                }
            }
            emitAll(
                combine(sources) { decisions ->
                    when {
                        ScheduledWakeAdmission.Drop in decisions -> ScheduledWakeAdmission.Drop
                        ScheduledWakeAdmission.Defer in decisions -> ScheduledWakeAdmission.Defer
                        else -> ScheduledWakeAdmission.Allow
                    }
                },
            )
        }
    }

    companion object {
        /** Resolves only required sets; wakes without any exact causal reference leave publisher owners lazy. */
        fun create(
            delivery: WakeDelivery,
            owners: Lazy<Set<ScheduledWakeOwner>>,
            publishers: Lazy<Set<ScheduledEventOwner>>,
        ): WakeDeliveryAdmission? {
            val request = delivery.wake.request
            val wakeOwner = request.ownerFeature?.let { feature ->
                val owner = owners.value.singleOrNull { it.feature == feature }
                    ?: throw WakeOwnerUnavailableException()
                OwnedWakeAdmission(owner, request)
            }
            val publisherGates = publishingOwners(delivery, publishers).map { owner ->
                OwnedWakeAdmission(request) { owner.admission(delivery) }
            }
            return (listOfNotNull(wakeOwner) + publisherGates).takeIf { it.isNotEmpty() }?.let(::WakeDeliveryAdmission)
        }

        private fun publishingOwners(
            delivery: WakeDelivery,
            publishers: Lazy<Set<ScheduledEventOwner>>,
        ): List<ScheduledEventOwner> {
            val origin = (delivery.reason as? WakeReason.Event)?.event?.origin
            val featureOwners = if (origin is EventOrigin.Feature) {
                listOf(
                    publishers.value.singleOrNull { it.feature == origin.name }
                        ?: throw WakeOwnerUnavailableException(),
                )
            } else {
                emptyList()
            }
            val hasExactRequest = delivery.wake.request.initiator != null || origin is EventOrigin.HostTurn ||
                (origin is EventOrigin.Session && origin.request != null) ||
                (origin is EventOrigin.Action && origin.initiator != null)
            val observers = if (hasExactRequest) publishers.value.filter { it.isSessionOriginObserver } else emptyList()
            return (featureOwners + observers).distinct()
        }
    }
}
