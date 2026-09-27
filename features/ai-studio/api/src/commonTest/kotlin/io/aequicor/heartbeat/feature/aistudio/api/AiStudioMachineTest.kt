package io.aequicor.heartbeat.feature.aistudio.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiStudioMachineTest {
    @Test
    fun `placeholder starts ready without effects or generation transitions`() {
        assertEquals(AiStudioState, AiStudioMachineSpec.initial)
        assertTrue(AiStudioMachineSpec.transitions.isEmpty())
    }
}
