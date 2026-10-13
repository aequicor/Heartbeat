package io.aequicor.heartbeat.feature.chromiumbridge.impl.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptDiffTest {
    private data class Entry(val id: String, val text: String)

    private val diff = TranscriptDiff(Entry::id)

    @Test
    fun `the first snapshot resets the watcher`() {
        val first = listOf(Entry("a", "1"))

        assertEquals(TranscriptChange(true, first, emptyList()), diff.next(first))
    }

    @Test
    fun `appended and updated entries are upserted and gone ones removed`() {
        diff.next(listOf(Entry("a", "1"), Entry("b", "streaming")))

        assertEquals(
            TranscriptChange(false, listOf(Entry("b", "done"), Entry("c", "new")), emptyList()),
            diff.next(listOf(Entry("a", "1"), Entry("b", "done"), Entry("c", "new"))),
        )
        assertEquals(
            TranscriptChange(false, emptyList(), listOf("a")),
            diff.next(listOf(Entry("b", "done"), Entry("c", "new"))),
        )
    }

    @Test
    fun `an unchanged snapshot produces nothing`() {
        diff.next(listOf(Entry("a", "1")))

        assertNull(diff.next(listOf(Entry("a", "1"))))
    }

    @Test
    fun `insertions before known entries and reorders reset the watcher`() {
        diff.next(listOf(Entry("a", "1"), Entry("b", "2")))

        assertEquals(true, diff.next(listOf(Entry("x", "0"), Entry("a", "1"), Entry("b", "2")))?.isReset)
        assertEquals(true, diff.next(listOf(Entry("a", "1"), Entry("x", "0"), Entry("b", "2")))?.isReset)
    }
}
