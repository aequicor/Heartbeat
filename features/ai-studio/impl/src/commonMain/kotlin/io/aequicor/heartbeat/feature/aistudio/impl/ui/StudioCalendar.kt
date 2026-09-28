package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Local day and zone stay stable between elapsed-time ticks, allowing unchanged panes to skip recomposition. */
@Immutable
internal data class StudioCalendar(val zone: TimeZone = TimeZone.UTC, val today: LocalDate? = null) {
    fun day(instant: Instant): LocalDate = instant.toLocalDateTime(zone).date

    fun dateLabel(instant: Instant, todayLabel: String, yesterdayLabel: String): String {
        val date = day(instant)
        return when {
            date == today -> todayLabel
            today?.let { date.daysUntil(it) == 1 } == true -> yesterdayLabel
            else -> "${date.day.pad()}.${(date.month.ordinal + 1).pad()}.${date.year}"
        }
    }

    fun timeLabel(instant: Instant): String = instant.toLocalDateTime(zone).let {
        "${it.hour.pad()}:${it.minute.pad()}"
    }
}

/** Derives the local day from the injected screen clock; unknown clocks never invent a relative date. */
internal fun studioCalendar(now: Instant): StudioCalendar {
    val zone = TimeZone.currentSystemDefault()
    return StudioCalendar(zone, now.takeUnless { it == Instant.DISTANT_PAST }?.toLocalDateTime(zone)?.date)
}

private fun Int.pad(): String = toString().padStart(2, '0')
