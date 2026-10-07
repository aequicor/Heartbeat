package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import kotlinx.coroutines.flow.Flow

/**
 * Admission for every wake triggered by [EventOrigin.Feature] with the matching [feature], including wakes
 * created by an agent or another feature. Exactly one publisher owner must exist; missing or ambiguous owners
 * refuse delivery. This gate intersects the wake owner's gate and never replaces its ownership or authority.
 * Contributions and their service dependencies must remain lazy while unused.
 */
public interface ScheduledEventOwner {
    /** Host-assigned publisher namespace, matching [EventOrigin.Feature.name]. */
    public val feature: String

    /**
     * Also observes [EventOrigin.Session] deliveries carrying an exact request identity and trusted
     * [EventOrigin.HostTurn] notifications and wakes carrying a trusted initiating request, including deadlines.
     * All opted-in observers intersect; an observer must allow ancestry it
     * does not own. Defaults to publisher-only admission.
     * Reading this property must not construct a runtime or start feature work.
     */
    public val isSessionOriginObserver: Boolean get() = false

    /**
     * Current admission for one immutable Feature delivery, or an opted-in exact request causal relay,
     * including its original event and opaque host metadata. Session observations must allow unknown ancestry.
     * Inspect both the wake initiator and trigger origin; restrictions from independent causes combine.
     * Emit the current decision immediately, then changes. The host collects again at the native submission
     * boundary; correlation bookkeeping must be idempotent for the same delivery request. Refusal stays sticky
     * for that attempt. A deadline cannot defer. Never infer authority from event payload or render metadata
     * into prompts, diagnostics or logs. Missing or malformed required publisher metadata must refuse delivery.
     */
    public fun admission(delivery: WakeDelivery): Flow<ScheduledWakeAdmission>
}
