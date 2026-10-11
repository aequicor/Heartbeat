package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessEventAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HARNESS_WAKE_OWNER
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessAccess
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformLatest

/** Shared write-before-Allow boundary. Construction leaves authority and persistent storage lazy. */
@Inject
internal class HarnessSchedulerAdmission(
    private val access: Lazy<HarnessSchedulerAccess>,
    private val ancestry: HarnessEventAncestry,
    private val storage: Lazy<HarnessRequestAncestry>,
    private val streaks: HarnessSessionTurnStreaks,
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

    /**
     * While provenance is still restoring after startup an event-only wake defers to producer replay; a deadline
     * cannot defer, so it waits for the first known decision within the scheduler's initial admission window.
     * A denied publisher drops harness wakes, but only defers another owner's event-only wake: the publisher's
     * authority may return, and that wake was never the harness's to discard. An owned wake that continues a
     * harness chain is dropped while its session's turn streak is exhausted, live until native submission; owned
     * wakes without harness ancestry (a workflow's completion wake started by the agent) are not part of a streak.
     */
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
        combine(
            access.value.permits(owner.harness, target?.let { HarnessTarget(it.session, it.workspace) }),
            target?.let { streaks.exhausted(it.session) } ?: flowOf(false),
            ::Pair,
        ).transformLatest { (decision, isStreakExhausted) ->
            when (decision) {
                HarnessAccess.Unknown -> if (request.condition.deadline == null) emit(ScheduledWakeAdmission.Defer)
                HarnessAccess.Denied -> emit(denied(request, target))
                is HarnessAccess.Permitted -> emit(permitted(request, decision, isStreakExhausted, resolve))
            }
        }
    }

    private fun denied(request: WakeRequest, target: WakeRequest?): ScheduledWakeAdmission =
        if (target == null && request.isForeignEventWake()) {
            ScheduledWakeAdmission.Defer
        } else {
            ScheduledWakeAdmission.Drop
        }

    /** A held event-only wake would keep its quota until a user turn, so an exhausted streak drops it as well. */
    private suspend fun permitted(
        request: WakeRequest,
        decision: HarnessAccess.Permitted,
        isStreakExhausted: Boolean,
        resolve: suspend () -> HarnessCallOrigin,
    ): ScheduledWakeAdmission {
        val origin = resolve()
        if (isStreakExhausted && origin.isHarnessChain) {
            log.d { "Drop harness wake: session turn streak reached" }
            return ScheduledWakeAdmission.Drop
        }
        persist(request, origin)
        val isCurrent = decision.permit.isCurrent()
        currentCoroutineContext().ensureActive()
        return if (isCurrent) ScheduledWakeAdmission.Allow else ScheduledWakeAdmission.Drop
    }

    private suspend fun persist(request: WakeRequest, origin: HarnessCallOrigin) {
        if (origin.isHarnessChain) {
            storage.value.restrict(request.session, request.id.deliveryRequestId(), origin)
        }
        currentCoroutineContext().ensureActive()
    }

    /** Admission runs only host code (codec, Room, registry), so its original failure is logged as is. */
    private fun guarded(source: () -> Flow<ScheduledWakeAdmission>): Flow<ScheduledWakeAdmission> = flow {
        emitAll(source())
    }.catch { error ->
        if (error is CancellationException || error !is Exception) throw error
        log.w(error) { "Reject wake with unavailable harness admission" }
        emit(ScheduledWakeAdmission.Drop)
    }
}

/** Another feature's event-only wake; it can wait for the next event instead of being discarded. */
private fun WakeRequest.isForeignEventWake(): Boolean = ownerFeature != HARNESS_WAKE_OWNER && condition.deadline == null

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
