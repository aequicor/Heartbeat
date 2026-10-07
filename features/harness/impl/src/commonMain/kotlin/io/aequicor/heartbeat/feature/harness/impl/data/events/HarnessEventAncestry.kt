package io.aequicor.heartbeat.feature.harness.impl.data.events

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessOwnedContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HARNESS_WAKE_OWNER
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.deliveryRequestId
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperPromptAttempt

/**
 * Resolves only trusted host references. Independent causes combine monotonically; payload, titles and the latest
 * request of a session are never consulted. Corruption and IO propagate so dispatch cannot become neutral.
 */
@Inject
internal class HarnessEventAncestry(private val requests: Lazy<HarnessRequestAncestry>) {
    private val ownership = HarnessOwnedContext()

    suspend fun origin(origin: EventOrigin): HarnessCallOrigin = when (origin) {
        is EventOrigin.Session -> origin.request?.let { request(RequestInitiator(origin.session, it)) }
            ?: HarnessCallOrigin()

        is EventOrigin.HostTurn -> request(RequestInitiator(origin.session, origin.request))

        is EventOrigin.Action -> request(origin.initiator)

        is EventOrigin.Feature -> if (origin.name == HARNESS_WAKE_OWNER) {
            checkNotNull(ownership.decode(origin.context)) { "Invalid harness event ancestry" }.origin()
        } else {
            HarnessCallOrigin()
        }

        EventOrigin.Host, EventOrigin.System -> HarnessCallOrigin()
    }

    suspend fun wake(wake: WakeRequest): HarnessCallOrigin {
        val owned = if (wake.ownerFeature == HARNESS_WAKE_OWNER) {
            checkNotNull(ownership.decode(wake.ownerContext)) { "Invalid harness wake ancestry" }.origin()
        } else {
            HarnessCallOrigin()
        }
        val delivered = requests.value.lookup(wake.session, wake.id.deliveryRequestId()) ?: HarnessCallOrigin()
        return owned.merge(request(wake.initiator)).merge(delivered)
    }

    suspend fun delivery(delivery: WakeDelivery): HarnessCallOrigin =
        wake(delivery.wake.request).merge(reason(delivery.reason))

    /** First and recovery helper prompts merge immutable handoff with any previously persisted exact target. */
    suspend fun helper(attempt: HelperPromptAttempt): HarnessCallOrigin {
        val handoff = attempt.handoff
        val owned = if (handoff?.ownerFeature == HARNESS_WAKE_OWNER) {
            checkNotNull(ownership.decode(handoff.ownerContext)) { "Invalid harness helper ancestry" }.origin()
        } else {
            HarnessCallOrigin()
        }
        val prior = requests.value.lookup(attempt.session, attempt.request) ?: HarnessCallOrigin()
        return owned.merge(request(handoff?.initiator)).merge(prior)
    }

    suspend fun output(output: SchedulerOutput): HarnessCallOrigin = when (output) {
        is SchedulerOutput.Scheduled -> wake(output.wake.request)

        is SchedulerOutput.Woke -> wake(output.wake.request).merge(reason(output.reason))

        is SchedulerOutput.Rejected -> merge(listOfNotNull(output.origin)).merge(request(output.initiator))
            .merge(request(output.deliveryRequest))

        is SchedulerOutput.Cancelled -> merge(output.origins)

        is SchedulerOutput.DeliveryFailed -> {
            var combined = merge(output.origins)
            output.wakes.forEach { combined = combined.merge(wake(it.request)) }
            combined
        }

        is SchedulerOutput.Deferred -> HarnessCallOrigin()
    }

    private suspend fun request(initiator: RequestInitiator?): HarnessCallOrigin = initiator?.let {
        requests.value.lookup(it.session, it.request)
    } ?: HarnessCallOrigin()

    private suspend fun reason(reason: WakeReason): HarnessCallOrigin = when (reason) {
        is WakeReason.Event -> origin(reason.event.origin)
        is WakeReason.Deadline -> HarnessCallOrigin()
    }

    private suspend fun merge(origins: List<EventOrigin>): HarnessCallOrigin {
        var combined = HarnessCallOrigin()
        origins.forEach { combined = combined.merge(origin(it)) }
        return combined
    }
}
