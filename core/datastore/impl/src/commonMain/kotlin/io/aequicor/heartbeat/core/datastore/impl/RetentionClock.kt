package io.aequicor.heartbeat.core.datastore.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.RecordRetention
import io.aequicor.heartbeat.core.datastore.RecordRetentions
import io.aequicor.heartbeat.core.datastore.Retention
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Time zone of [Expiry.Daily]; injected so tests do not depend on the machine. */
internal fun interface TimeZoneProvider {
    fun current(): TimeZone
}

@ContributesBinding(AppScope::class)
@Inject
internal class SystemTimeZoneProvider : TimeZoneProvider {
    override fun current(): TimeZone = TimeZone.currentSystemDefault()
}

/** Wall-clock time of retention, in epoch millis — the unit stored on disk. */
@Inject
internal class RetentionClock(private val clock: Clock, private val timeZones: TimeZoneProvider) {

    fun now(): Long = clock.now().toEpochMilliseconds()

    /** Deadline of a record written at [now] with [expiry], or `null` when it does not expire by time. */
    fun deadline(expiry: Expiry?, now: Long): Long? = when (expiry) {
        null -> null
        is Expiry.After -> now + expiry.ttl.inWholeMilliseconds
        is Expiry.At -> expiry.instant.toEpochMilliseconds()
        is Expiry.Daily -> nextDaily(expiry, now)
    }

    private fun nextDaily(expiry: Expiry.Daily, now: Long): Long {
        val zone = timeZones.current()
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        val candidate = today.atTime(expiry.time).toInstant(zone).toEpochMilliseconds()
        return if (candidate > now) {
            candidate
        } else {
            today.plus(1, DateTimeUnit.DAY).atTime(expiry.time).toInstant(zone).toEpochMilliseconds()
        }
    }
}

@ContributesBinding(AppScope::class)
@Inject
internal class RecordRetentionsImpl(private val clock: RetentionClock) : RecordRetentions {
    override fun stamp(retention: Retention): RecordRetention {
        val now = clock.now()
        return RecordRetention(
            createdAt = now,
            expiresAt = clock.deadline(retention.expiry, now),
            event = retention.event?.name,
        )
    }
}
