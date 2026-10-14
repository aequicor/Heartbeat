package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDocumentMeasurementsTest {
    @Test
    fun `size reports coalesce and can shrink after content changes`() {
        val queued = mutableListOf<() -> Unit>()
        val measurements = DesktopDocumentMeasurements { queued += it }
        val page = measurements.beginPage()
        repeat(100) { measurements.offer("$page:600:${800 + it}") }
        assertEquals(1, queued.size)
        queued.removeAt(0)()
        assertEquals(DesktopDocumentSize(600, 899), measurements.size.value)

        measurements.offer("$page:600:120")
        queued.removeAt(0)()
        assertEquals(DesktopDocumentSize(600, 120), measurements.size.value)
    }

    @Test
    fun `a new page and close revoke queued measurements`() {
        val queued = mutableListOf<() -> Unit>()
        val measurements = DesktopDocumentMeasurements { queued += it }
        val first = measurements.beginPage()
        measurements.offer("$first:600:800")
        val second = measurements.beginPage()
        queued.removeAt(0)()
        assertNull(measurements.size.value)
        measurements.offer("$first:600:900")
        assertEquals(0, queued.size)
        measurements.offer("$second:600:100")
        measurements.close()
        queued.removeAt(0)()
        measurements.offer("$second:600:200")
        assertNull(measurements.size.value)
        assertEquals(0, queued.size)
    }

    @Test
    fun `malformed and oversized reports cannot allocate layout work`() {
        val queued = mutableListOf<() -> Unit>()
        val measurements = DesktopDocumentMeasurements { queued += it }
        val page = measurements.beginPage()
        listOf(
            "$page:0:100", "$page:600:0", "$page:600:-1", "$page:600:100001", "$page:100001:10",
            "$page:NaN:100", "$page:600:Infinity", "$page:600:100:extra", "9".repeat(49), "{}",
        ).forEach(measurements::offer)
        assertNull(measurements.size.value)
        assertEquals(0, queued.size)
    }

    @Test
    fun `CSS pixels map through measured width rather than physical screen density`() {
        val size = DesktopDocumentSize(width = 600, height = 300)
        assertEquals(300f, size.logicalHeight(600f))
        assertEquals(150f, size.logicalHeight(300f))
        assertNull(size.logicalHeight(0f))
        assertNull(size.logicalHeight(Float.NaN))
    }
}
