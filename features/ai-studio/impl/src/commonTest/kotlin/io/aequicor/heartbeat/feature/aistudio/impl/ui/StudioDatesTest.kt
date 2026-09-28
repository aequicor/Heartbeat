package io.aequicor.heartbeat.feature.aistudio.impl.ui

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StudioDatesTest {
    @Test
    fun `date grouping follows the local day rather than UTC midnight`() {
        val calendar = StudioCalendar(TimeZone.of("Europe/Samara"), LocalDate(2026, 9, 28))
        val instant = Instant.parse("2026-09-27T21:30:00Z")
        assertEquals(LocalDate(2026, 9, 28), calendar.day(instant))
        assertEquals("Сегодня", calendar.dateLabel(instant, "Сегодня", "Вчера"))
        assertEquals("01:30", calendar.timeLabel(instant))
    }

    @Test
    fun `yesterday is a calendar day across the daylight saving transition`() {
        val calendar = StudioCalendar(TimeZone.of("America/New_York"), LocalDate(2026, 3, 9))
        assertEquals(
            "Вчера",
            calendar.dateLabel(Instant.parse("2026-03-08T05:30:00Z"), "Сегодня", "Вчера"),
        )
    }

    @Test
    fun `older dates and unknown current day retain an absolute date`() {
        val instant = Instant.parse("2025-12-31T12:04:00Z")
        val calendar = StudioCalendar(TimeZone.UTC, LocalDate(2026, 9, 28))
        assertEquals("31.12.2025", calendar.dateLabel(instant, "Сегодня", "Вчера"))
        assertEquals("31.12.2025", StudioCalendar().dateLabel(instant, "Сегодня", "Вчера"))
        assertEquals("12:04", calendar.timeLabel(instant))
    }
}
