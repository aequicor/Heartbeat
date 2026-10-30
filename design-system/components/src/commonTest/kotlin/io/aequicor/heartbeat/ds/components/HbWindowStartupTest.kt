package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HbWindowStartupTest {
    @Test
    fun waitsForInitialContentAndAllOverlappingLoaders() {
        val state = HbWindowStartupState()
        val profile = Any()
        val studio = Any()
        assertFalse(state.isReady)
        state.beginLoading(profile)
        state.contentCommitted()
        assertFalse(state.isReady)
        state.beginLoading(studio)
        state.endLoading(profile)
        assertFalse(state.isReady)
        state.endLoading(studio)
        assertTrue(state.isReady)
    }
}
