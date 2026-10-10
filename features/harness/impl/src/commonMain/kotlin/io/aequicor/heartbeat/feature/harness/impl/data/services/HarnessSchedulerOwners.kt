package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessEventAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HARNESS_WAKE_OWNER
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.deliveryRequestId
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledEventOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/** Shared write-before-Allow boundary. Construction leaves authority and persistent storage lazy. */
@Inject
internal class HarnessSchedulerAdmission(
    private val access: Lazy<HarnessSchedulerAccess>,
    private val ancestry: HarnessEventAncestry,
    private val storage: Lazy<HarnessRequestAncestry>,
) {
    private val ownership = HarnessOwnedContext()
    private val log = Log.tag("HarnessSchedulerAdmission")

    fun wake(request: WakeRequest): Flow<ScheduledWakeAdmission> = guarded {
        check(request.ownerFeature == HARNESS_WAKE_OWNER) { "Unexpected wake owner" }
        val owner = checkNotNull(ownership.decode(request.ownerContext)) { "Missing wake owner" }
        admit(request, owner, request) { ancestry.wake(request) }
    }

    fun event(delivery: WakeDelivery): Flow<ScheduledWakeAdmission> = guarded {
        val origin = (delivery.reason as? WakeReason.Event)?.event?.origin
        val publisher = (origin as? EventOrigin.Feature)?.takeIf { it.name == HARNESS_WAKE_OWNER }?.let {
            checkNotNull(ownership.decode(it.context)) { "Missing event publisher" }
        }
        // Exact session/action relays retain restrictions even while every harness is disabled.
        admit(delivery.wake.request, publisher, null) { ancestry.delivery(delivery) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun admit(
        request: WakeRequest,
        owner: HarnessOwnership?,
        target: WakeRequest?,
        resolve: suspend () -> HarnessCallOrigin,
    ): Flow<ScheduledWakeAdmission> = if (owner == null) {
        flow {
            persist(request, resolve())
            emit(ScheduledWakeAdmission.Allow)
        }
    } else {
        access.value.permits(
            owner.harness,
            target?.let { HarnessTarget(it.session, it.workspace) },
        ).transformLatest { permit ->
            if (permit == null) {
                emit(ScheduledWakeAdmission.Drop)
            } else {
                persist(request, resolve())
                val isCurrent = permit.isCurrent()
                currentCoroutineContext().ensureActive()
                emit(if (isCurrent) ScheduledWakeAdmission.Allow else ScheduledWakeAdmission.Drop)
            }
        }
    }

    private suspend fun persist(request: WakeRequest, origin: HarnessCallOrigin) {
        if (origin.isHookRestricted || origin.sendChain.isNotEmpty()) {
            storage.value.restrict(request.session, request.id.deliveryRequestId(), origin)
        }
        currentCoroutineContext().ensureActive()
    }

    private fun guarded(source: () -> Flow<ScheduledWakeAdmission>): Flow<ScheduledWakeAdmission> = flow {
        emitAll(source())
    }.catch { error ->
        if (error is CancellationException || error !is Exception) throw error
        log.w(harnessScriptFailure(error)) { "Reject wake with unavailable harness admission" }
        emit(ScheduledWakeAdmission.Drop)
    }
}

/** Owned wakes require both current target activation and durable ancestry before native preparation. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessScheduledWakeOwner(private val admission: HarnessSchedulerAdmission) : ScheduledWakeOwner {
    override val feature: String = HARNESS_WAKE_OWNER
    override fun admission(request: WakeRequest): Flow<ScheduledWakeAdmission> = admission.wake(request)
}

/** Publisher authority is independent of wake ownership; exact session/action causes are monotone relays. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessScheduledEventOwner(private val admission: HarnessSchedulerAdmission) : ScheduledEventOwner {
    override val feature: String = HARNESS_WAKE_OWNER
    override val isSessionOriginObserver: Boolean = true
    override fun admission(delivery: WakeDelivery): Flow<ScheduledWakeAdmission> = admission.event(delivery)
}
