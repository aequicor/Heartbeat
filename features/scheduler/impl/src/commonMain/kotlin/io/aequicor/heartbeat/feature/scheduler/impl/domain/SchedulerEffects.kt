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
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDeferredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads and stores wakes and delivers due ones through the session host that owns the session. Writes are serialised
 * and an older revision never overwrites a newer one. Every delivery settles on its own, so one unreachable session
 * does not hold back the others.
 */
internal class SchedulerEffects(
    private val storage: WakeStorage,
    // Lazy: hosts reach the engine runtime, which must not start with the profile while nothing is due.
    private val hosts: Lazy<Set<ScheduledSessionHost>>,
) : EffectHandler<SchedulerEffect, SchedulerIntent> {
    private val log = Log.tag("SchedulerEffects")
    private val writes = Mutex()
    private var stored = -1L

    override suspend fun handle(effect: SchedulerEffect, machine: EffectScope<SchedulerIntent>) {
        when (effect) {
            SchedulerEffect.Load -> machine.send(SchedulerIntent.Internal.Loaded(storage.load()))

            is SchedulerEffect.Persist -> persist(effect)

            is SchedulerEffect.Deliver -> coroutineScope {
                effect.deliveries.forEach { delivery -> launch { deliver(delivery, machine) } }
            }
        }
    }

    private suspend fun persist(effect: SchedulerEffect.Persist) = writes.withLock {
        if (effect.revision <= stored) {
            log.v { "skip stale write revision=${effect.revision}" }
            return@withLock
        }
        storage.save(effect.wakes)
        stored = effect.revision
    }

    private suspend fun deliver(delivery: WakeDelivery, machine: EffectScope<SchedulerIntent>) {
        val id = delivery.wake.id
        val failure = try {
            val host = hostFor(delivery.wake.request)
            log.i { "deliver wake $id via ${host::class.simpleName.orEmpty()}" }
            // A session busy for too long drops the wake rather than holding it undeliverable and uncancellable.
            val isDelivered = withTimeoutOrNull(SchedulerLimits.DELIVERY_TIMEOUT) {
                host.wake(delivery.wake.request, wakePrompt(delivery))
                true
            } == true
            if (isDelivered) null else WakeFailure.Busy.also { log.w { "wake $id: the session stayed busy" } }
        } catch (e: CancellationException) {
            throw e
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

    private suspend fun hostFor(request: WakeRequest): ScheduledSessionHost =
        hosts.value.sortedByDescending { it.priority }.firstOrNull { it.owns(request.session) }
            ?: throw SessionUnavailableException("No host owns the session")
}
