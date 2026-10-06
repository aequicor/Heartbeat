package io.aequicor.heartbeat.feature.scheduler.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEffect
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDeferredException
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDroppedException
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads and stores wakes and delivers due ones through the session host that owns the session. Writes are serialised
 * and an older revision never overwrites a newer one. Every delivery settles on its own, so one unreachable session
 * does not hold back the others.
 */
internal class SchedulerEffects(
    private val persistence: SchedulerPersistence,
    // Lazy: hosts reach the engine runtime, which must not start with the profile while nothing is due.
    private val hosts: Lazy<Set<ScheduledSessionHost>>,
    private val owners: Lazy<Set<ScheduledWakeOwner>> = lazyOf(emptySet()),
) : EffectHandler<SchedulerEffect, SchedulerIntent> {
    private val log = Log.tag("SchedulerEffects")

    override suspend fun handle(effect: SchedulerEffect, machine: EffectScope<SchedulerIntent>) {
        when (effect) {
            SchedulerEffect.Load -> machine.send(SchedulerIntent.Internal.Loaded(persistence.load()))

            is SchedulerEffect.Persist -> persistence.persist(effect)

            is SchedulerEffect.Deliver -> coroutineScope {
                effect.deliveries.forEach { delivery -> launch { deliver(delivery, machine) } }
            }
        }
    }

    private suspend fun deliver(delivery: WakeDelivery, machine: EffectScope<SchedulerIntent>) {
        val deliveryContext = currentCoroutineContext()
        var hasSettled = false
        try {
            deliverAttempt(delivery, machine)
            hasSettled = true
        } finally {
            // A source/host can cancel itself while the delivery scope is still live. Child cancellation alone
            // does not fail the parent Deliver effect, so settle the wake before propagating that cancellation.
            if (!hasSettled && deliveryContext.isActive) {
                log.w { "Wake delivery ended without a result; refusing the attempt" }
                withContext(NonCancellable) {
                    machine.send(SchedulerIntent.Internal.DeliveryFailed(listOf(delivery.wake.id), WakeFailure.Unknown))
                }
            }
        }
    }

    private suspend fun deliverAttempt(delivery: WakeDelivery, machine: EffectScope<SchedulerIntent>) {
        val id = delivery.wake.id
        var admission: OwnedWakeAdmission? = null
        val failure = try {
            admission = admissionFor(delivery.wake.request)
            // A session busy for too long drops the wake rather than holding it undeliverable and uncancellable.
            val isDelivered = withTimeoutOrNull(SchedulerLimits.DELIVERY_TIMEOUT) {
                checkAdmission(admission)
                val host = hostFor(delivery.wake.request)
                log.i { "deliver wake $id via ${host::class.simpleName.orEmpty()}" }
                host.wake(delivery.wake.request, wakePrompt(delivery).copy(admission = admission?.decisions))
                true
            } == true
            if (isDelivered) null else WakeFailure.Busy.also { log.w { "wake $id: the session stayed busy" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WakeOwnerUnavailableException) {
            log.w(e) { "wake $id: owner unavailable" }
            WakeFailure.OwnerUnavailable
        } catch (e: ScheduledWakeDroppedException) {
            log.w(e) { "wake $id: owner rejected admission" }
            admission?.failure ?: WakeFailure.OwnerRejected
        } catch (e: ScheduledWakeDeferredException) {
            log.w(e) { "wake $id: admission paused; keep pending until event replay" }
            machine.send(SchedulerIntent.Internal.Deferred(id))
            return
        } catch (e: SessionUnavailableException) {
            log.w(e) { "wake $id: session unavailable" }
            WakeFailure.SessionUnavailable
        } catch (e: EngineException) {
            log.w(e) { "wake $id: engine refused the prompt" }
            WakeFailure.Engine
        } catch (e: Exception) {
            log.e(e) { "wake $id: delivery failed" }
            WakeFailure.Unknown
        }
        if (failure == null) {
            machine.send(SchedulerIntent.Internal.Delivered(id, delivery.reason))
        } else {
            machine.send(SchedulerIntent.Internal.DeliveryFailed(listOf(id), failure))
        }
    }

    private suspend fun checkAdmission(admission: OwnedWakeAdmission?) {
        when (admission?.decisions?.first()) {
            ScheduledWakeAdmission.Defer -> throw ScheduledWakeDeferredException()
            ScheduledWakeAdmission.Drop -> throw ScheduledWakeDroppedException()
            ScheduledWakeAdmission.Allow, null -> Unit
        }
    }

    private fun admissionFor(request: WakeRequest): OwnedWakeAdmission? {
        val feature = request.ownerFeature ?: return null
        val owner = owners.value.singleOrNull { it.feature == feature } ?: throw WakeOwnerUnavailableException()
        return OwnedWakeAdmission(owner, request)
    }

    private suspend fun hostFor(request: WakeRequest): ScheduledSessionHost =
        hosts.value.sortedByDescending { it.priority }.firstOrNull {
            (request.ownerFeature == null || it.isWakeAdmissionSupported) && it.owns(request.session)
        }
            ?: throw SessionUnavailableException("No host owns the session")
}
