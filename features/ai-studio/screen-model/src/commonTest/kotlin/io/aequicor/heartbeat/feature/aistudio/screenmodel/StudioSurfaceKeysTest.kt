package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class StudioSurfaceKeysTest {
    @Test
    fun `the same card has independent hosts in different panes`() {
        val cards = mapOf(studioCardKey(1, "card") to "first", studioCardKey(10, "card") to "second")
        assertEquals(mapOf("card" to "first"), paneCards(cards, 1))
        assertEquals(mapOf("card" to "second"), paneCards(cards, 10))
        assertEquals(emptyMap(), paneCards(cards, 2))
    }
}
