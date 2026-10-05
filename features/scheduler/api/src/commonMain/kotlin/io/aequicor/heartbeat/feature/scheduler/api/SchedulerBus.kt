package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Who published a bus event. */
@Serializable
public sealed interface EventOrigin {
    /** The host itself (lifecycle events, features). */
    @Serializable
    public data object Host : EventOrigin

    /** A platform signal. */
    @Serializable
    public data object System : EventOrigin

    /** An agent of [session] through a hosted tool. */
    @Serializable
    public data class Session(val session: SessionRef) : EventOrigin

    /** A background action. */
    @Serializable
    public data class Action(val action: ActionId) : EventOrigin
}

/**
 * One event on the profile bus. [payload] is free text up to [SchedulerLimits.MAX_PAYLOAD] characters handed to the
 * woken agent; it is never logged.
 */
public data class BusEvent(val key: EventKey, val origin: EventOrigin, val at: Instant, val payload: String? = null) {
    init {
        require((payload?.length ?: 0) <= SchedulerLimits.MAX_PAYLOAD) { "Event payload is too long" }
    }

    override fun toString(): String =
        "BusEvent(key=$key, origin=${origin::class.simpleName.orEmpty()}, at=$at, payload=${payload?.length ?: 0} chars)"
}

/**
 * The single event bus of a profile: platform signals, session lifecycle, action results and named signals all
 * travel here under an [EventKey]. The stream is hot and has no replay: an event published while nobody listens,
 * or while the profile is closed, is lost. Durability of waits belongs to the scheduler machine, which keeps pending
 * wakes and always listens while the profile is open. Safe to call from any thread.
 */
public interface SchedulerBus {
    /** Every event published from now on. */
    public val events: Flow<BusEvent>

    /** Publishes an event stamped with the current time and returns it. */
    public suspend fun publish(key: EventKey, origin: EventOrigin, payload: String? = null): BusEvent
}
