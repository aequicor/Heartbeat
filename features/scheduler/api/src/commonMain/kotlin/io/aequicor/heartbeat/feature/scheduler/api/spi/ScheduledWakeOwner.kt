package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.flow.Flow

/**
 * Profile contribution controlling admission of wakes with [WakeRequest.ownerFeature] equal to [feature].
 * Resolution requires exactly one matching owner; missing or duplicate owners refuse delivery. Implementations
 * expose current state immediately, then changes, without starting scripts, machines or engine work. Collection
 * may happen more than once, including a fresh check at the native submission boundary.
 */
public interface ScheduledWakeOwner {
    /** Stable feature namespace, independent of the display label of a wake. */
    public val feature: String

    /** Live admission for this immutable delivery attempt, including deletion of its owning item. */
    public fun admission(request: WakeRequest): Flow<ScheduledWakeAdmission>
}

/** A host may revoke preparation, but never a native turn whose submission has already started. */
public enum class ScheduledWakeAdmission {
    /** Preparation and submission may proceed. */
    Allow,

    /** Retain an event-only wake for producer replay. Invalid for wakes with deadlines (would spin on the timer). */
    Defer,

    /** Drop this wake without submitting a prompt. */
    Drop,
}

/** The owner withdrew admission before native submission; the scheduler must drop the wake. */
public class ScheduledWakeDroppedException : Exception("The owning feature rejected wake admission")
