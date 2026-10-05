package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Identity of a scheduled wake, chosen by the caller; `[a-z0-9_-]`, at most 64 characters. */
@Serializable
public data class WakeId(val value: String) {
    init {
        require(isValidId(value)) { "Invalid WakeId" }
    }

    override fun toString(): String = value

    /** Parsing of ids given by agents. */
    public companion object {
        /** A valid id, or null. */
        public fun parse(value: String): WakeId? = value.trim().takeIf(::isValidId)?.let(::WakeId)
    }
}

/** Identity of a background action; also a segment of [EventKeys.actionFinished]. */
@Serializable
public data class ActionId(val value: String) {
    init {
        require(isValidId(value)) { "Invalid ActionId" }
    }

    override fun toString(): String = value

    /** Parsing of ids given by agents. */
    public companion object {
        /** A valid id, or null. */
        public fun parse(value: String): ActionId? = value.trim().takeIf(::isValidId)?.let(::ActionId)
    }
}

/**
 * When a sleeping session wakes: on the first bus event whose key is in [events], or at [deadline], whichever comes
 * first. A timer is a deadline computed by the caller; a deadline in the past wakes on the next tick.
 */
@Serializable
public data class WakeCondition(val events: Set<EventKey> = emptySet(), val deadline: Instant? = null) {
    init {
        require(events.isNotEmpty() || deadline != null) { "A wake needs an event or a deadline" }
        require(events.size <= SchedulerLimits.MAX_EVENTS) { "Too many wake events" }
    }
}

/** Who asked for a wake; shown back to the agent and in logs (type only). */
@Serializable
public sealed interface WakeOrigin {
    /** A hosted tool call of the sleeping session's agent during [turn]. */
    @Serializable
    public data class Agent(val turn: TurnId) : WakeOrigin

    /** Another feature, by its [name]. */
    @Serializable
    public data class Feature(val name: String) : WakeOrigin
}

/**
 * A request to wake [session] when [condition] holds. [note] is the sleeper's reminder of what to do next; it is
 * delivered back verbatim and never logged. [workspace] is the session's project, null for a chat without one.
 * [target] is the engine and model the session ran on; a host that resumes sessions itself needs it.
 */
@Serializable
public data class WakeRequest(
    val id: WakeId,
    val session: SessionRef,
    val workspace: WorkspaceRef?,
    val condition: WakeCondition,
    val note: String,
    val origin: WakeOrigin,
    val target: EngineTarget? = null,
) {
    init {
        require(note.length <= SchedulerLimits.MAX_NOTE) { "Wake note is too long" }
    }

    override fun toString(): String = "WakeRequest(id=$id, events=${condition.events.size}, " +
        "deadline=${condition.deadline ?: "none"}, origin=${origin::class.simpleName.orEmpty()})"
}

/** A pending wake, persisted in the profile until it is delivered or cancelled. */
@Serializable
public data class ScheduledWake(val request: WakeRequest, val createdAt: Instant) {
    /** Wake identity. */
    public val id: WakeId get() = request.id

    /** The sleeping session. */
    public val session: SessionRef get() = request.session

    /** Whether [event] wakes this session. */
    public fun matches(event: BusEvent): Boolean = event.key in request.condition.events

    /** Whether the deadline has passed at [now]. */
    public fun isDue(now: Instant): Boolean = request.condition.deadline?.let { it <= now } == true
}

/** Why a session woke. */
public sealed interface WakeReason {
    /** A matching bus event arrived. */
    public data class Event(val event: BusEvent) : WakeReason

    /** The deadline [at] passed. */
    public data class Deadline(val at: Instant) : WakeReason
}

/** Why a wake request was not scheduled. */
public enum class WakeRejection {
    /** A pending wake already has this id. */
    Duplicate,

    /** The session already has [SchedulerLimits.MAX_PER_SESSION] pending wakes. */
    SessionLimit,

    /** The profile already has [SchedulerLimits.MAX_PER_PROFILE] pending wakes. */
    ProfileLimit,

    /** The deadline is further than [SchedulerLimits.HORIZON] from now. */
    TooFar,
}

/** Why a due wake could not be delivered; the wake is dropped. */
public enum class WakeFailure {
    /** No host can resume the session (its chat was deleted or no engine serves it). */
    SessionUnavailable,

    /** The engine refused or lost the wake prompt. */
    Engine,

    /** The session stayed busy longer than [SchedulerLimits.DELIVERY_TIMEOUT]. */
    Busy,

    /** An unexpected error; details are in the log. */
    Unknown,
}

/** Bounds that keep a profile's schedule small and its prompts bounded. */
public object SchedulerLimits {
    /** Pending wakes per session. */
    public const val MAX_PER_SESSION: Int = 8

    /** Pending wakes per profile. */
    public const val MAX_PER_PROFILE: Int = 64

    /** Event keys per wake. */
    public const val MAX_EVENTS: Int = 16

    /** Characters of a wake note. */
    public const val MAX_NOTE: Int = 2_000

    /** Characters of an event payload. */
    public const val MAX_PAYLOAD: Int = 8_192

    /** How far ahead a deadline may be. */
    public val HORIZON: Duration = 30.days

    /** Deadline hosted tools give a wait for events only, so a signal that never comes cannot hold a slot forever. */
    public val EVENT_WAIT: Duration = 7.days

    /** How long a delivery waits for a busy session before the wake is dropped as [WakeFailure.Busy]. */
    public val DELIVERY_TIMEOUT: Duration = 1.hours
}

private const val MAX_ID = 64
private val ID_PATTERN = Regex("[a-z0-9_-]+")

private fun isValidId(value: String): Boolean = value.length <= MAX_ID && ID_PATTERN.matches(value)
