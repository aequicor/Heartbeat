package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.Serializable

/** First segment of an [EventKey]: who may publish events of this kind. */
public enum class EventNamespace(public val prefix: String) {
    /** Platform signals (network); published by the scheduler and [spi.SchedulerEventSource]s. */
    System("system"),

    /** Lifecycle of a session; published by the host only (see [EventKeys.turnFinished]). */
    Session("session"),

    /** Completion of a background action started by an agent (see [EventKeys.actionFinished]). */
    Action("action"),

    /** Named signals of agents and features (see [EventKeys.custom]). */
    Custom("custom"),
}

/**
 * The single address of a bus event: `<namespace>.<segment>…` in lower case, at most [MAX_LENGTH] characters, with a
 * known [EventNamespace]. Build keys with [EventKeys]; the raw constructor is for keys read back from storage or
 * from agent arguments, which it validates.
 */
@Serializable
public data class EventKey(val value: String) {
    init {
        require(isValid(value)) { "Invalid EventKey" }
    }

    /** Namespace of the key. */
    public val namespace: EventNamespace
        get() = EventNamespace.entries.first { it.prefix == value.substringBefore('.') }

    override fun toString(): String = value

    /** Limits and parsing of keys. */
    public companion object {
        /** Maximum length of a key. */
        public const val MAX_LENGTH: Int = 128

        /** A valid key, or null. */
        public fun parse(value: String): EventKey? = value.trim().takeIf(::isValid)?.let(::EventKey)

        private fun isValid(value: String): Boolean = value.length <= MAX_LENGTH &&
            KEY_PATTERN.matches(value) &&
            EventNamespace.entries.any { it.prefix == value.substringBefore('.') }
    }
}

/** Factories of every key the host understands. */
public object EventKeys {
    /** The device got a usable network connection. */
    public val NetworkAvailable: EventKey = EventKey("system.network.available")

    /** The device lost its network connection. */
    public val NetworkLost: EventKey = EventKey("system.network.lost")

    /**
     * A turn of [session] finished (any outcome). The key is stable across restarts: engine plus a 64-bit FNV-1a hash
     * of the full reference, so a session can be addressed without exposing its native id.
     */
    public fun turnFinished(session: SessionRef): EventKey =
        EventKey("${EventNamespace.Session.prefix}.${sessionSegment(session)}.turn_finished")

    /** The background action [action] finished; the payload describes the result. */
    public fun actionFinished(action: ActionId): EventKey =
        EventKey("${EventNamespace.Action.prefix}.${action.value}.finished")

    /** A named signal; [name] is one or more dot-separated `[a-z0-9_-]` segments, at most 64 characters. */
    public fun custom(name: String): EventKey {
        require(name.length <= MAX_CUSTOM_NAME && CUSTOM_NAME_PATTERN.matches(name)) { "Invalid signal name" }
        return EventKey("${EventNamespace.Custom.prefix}.$name")
    }

    /** Short stable token of [session] used inside session keys. */
    public fun sessionSegment(session: SessionRef): String {
        val engine = session.engine.value.lowercase().replace(UNSAFE_SEGMENT, "-").take(MAX_ENGINE_SEGMENT)
        val identity = "${session.engine.value}\u0000${session.source.value}\u0000${session.nativeId}"
        return "$engine.${fnv1a64(identity).toULong().toString(HEX_RADIX).padStart(HASH_DIGITS, '0')}"
    }
}

private const val MAX_CUSTOM_NAME = 64
private const val MAX_ENGINE_SEGMENT = 32
private const val HEX_RADIX = 16
private const val HASH_DIGITS = 16
private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
private const val BYTE_MASK = 0xff

private val KEY_PATTERN = Regex("[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)+")
private val CUSTOM_NAME_PATTERN = Regex("[a-z0-9_-]+(\\.[a-z0-9_-]+)*")
private val UNSAFE_SEGMENT = Regex("[^a-z0-9_-]")

private fun fnv1a64(text: String): Long = text.encodeToByteArray().fold(FNV_OFFSET) { hash, byte ->
    (hash xor (byte.toInt() and BYTE_MASK).toLong()) * FNV_PRIME
}
