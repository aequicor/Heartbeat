package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HbDateFieldTest {
    @Test
    fun `only calendar days written as YYYY-MM-DD are dates`() {
        assertTrue(isHbDate("2024-02-29"))
        assertTrue(isHbDate("2026-12-31"))
        assertFalse(isHbDate("2023-02-29"))
        assertFalse(isHbDate("2100-02-29"))
        assertTrue(isHbDate("2000-02-29"))
        assertFalse(isHbDate("2026-04-31"))
        assertFalse(isHbDate("2026-13-01"))
        assertFalse(isHbDate("2026-00-10"))
        assertFalse(isHbDate("2026-1-1"))
        assertFalse(isHbDate("tomorrow"))
    }

    @Test
    fun `date parts round-trip through the written form`() {
        assertEquals(HbDateParts(2026, 10, 12), hbDateParts("2026-10-12"))
        assertEquals("0987-03-04", HbDateParts(987, 3, 4).toString())
        assertNull(hbDateParts("2026-10-32"))
    }
}
