package io.aequicor.heartbeat.core.datastore

import kotlinx.datetime.LocalTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * How long one record (a key-value entry or a database row) lives. The core deletes it — features only declare it.
 *
 * [expiry] and [event] combine: the record is deleted by whichever comes first. Neither — the record lives as long
 * as its storage (see [StorageOwner]).
 *
 * ```
 * store.set(draftKey, text, Retention.expiring(Expiry.After(1.hours)))
 * store.set(tokenHintKey, hint, Retention.untilEvent(SignedOut))
 * ```
 */
public data class Retention(
    /** Time-based expiry, or `null` for none. */
    public val expiry: Expiry? = null,
    /** Event that deletes the record, or `null` for none. */
    public val event: DataEvent? = null,
) {
    /** `true` when the record is never deleted by the core. */
    public val isPermanent: Boolean get() = expiry == null && event == null

    override fun toString(): String = listOfNotNull(expiry?.toString(), event?.let { "until $it" })
        .joinToString(" or ")
        .ifEmpty { "permanent" }

    /** Factories of the common cases. */
    public companion object {
        /** Lives as long as its storage. */
        public val Permanent: Retention = Retention()

        /** Deleted by time. */
        public fun expiring(expiry: Expiry): Retention = Retention(expiry = expiry)

        /** Deleted when [event] is fired. */
        public fun untilEvent(event: DataEvent): Retention = Retention(event = event)
    }
}

/** Time-based expiry of a record; the deadline is fixed when the record is written. */
public sealed interface Expiry {

    /** [ttl] after the record is written. */
    public data class After(
        /** Time to live; positive. */
        public val ttl: Duration,
    ) : Expiry {
        init {
            require(ttl.isPositive() && ttl.isFinite()) { "ttl must be positive and finite: $ttl" }
        }

        override fun toString(): String = "expires in $ttl"
    }

    /** At [instant]; a record written after it expires immediately. */
    public data class At(
        /** Deadline. */
        public val instant: Instant,
    ) : Expiry {
        override fun toString(): String = "expires at $instant"
    }

    /** At the next [time] in the system time zone (the same day if it is still ahead, otherwise the next day). */
    public data class Daily(
        /** Local wall-clock time. */
        public val time: LocalTime,
    ) : Expiry {
        override fun toString(): String = "expires at next $time"
    }
}

/**
 * An event that deletes the records bound to it ([Retention.event]), fired by [DataStores.fire].
 * Declared by the feature that fires it: `internal val SignedOut = DataEvent("auth.signed_out")`.
 */
public data class DataEvent(
    /** Stable name: lowercase letters, digits, `_`, `.`, `-`. Stored on disk — never rename. */
    public val name: String,
) {
    init {
        require(EVENT_NAME.matches(name)) { "invalid event name '$name': expected ${EVENT_NAME.pattern}" }
    }

    override fun toString(): String = "event $name"

    private companion object {
        val EVENT_NAME = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    }
}
