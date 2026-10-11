package io.aequicor.heartbeat.feature.autocomplete.impl.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestionMatchingTest {
    @Test
    fun `prefix beats substring beats mismatch`() {
        assertEquals(0, queryRank("ver", "verify"))
        assertEquals(1, queryRank("rif", "verify"))
        assertEquals(-1, queryRank("zzz", "verify"))
    }

    @Test
    fun `matching is case-insensitive and an empty query matches everything`() {
        assertEquals(0, queryRank("VER", "Verify"))
        assertEquals(2, queryRank("", "anything"))
    }
}
