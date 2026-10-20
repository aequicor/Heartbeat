package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ContextUsageUi
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioUsageMappingsTest {
    @Test
    fun `unknown limit survives projection and screen transport without a zero percentage`() {
        val ui = ContextUsage(4096, null).toUi()
        assertEquals(4096L, ui.usedTokens)
        assertNull(ui.capacityTokens)
        assertNull(ui.percent)
        assertEquals(ui, Json.decodeFromString<ContextUsageUi>(Json.encodeToString(ui)))
    }

    @Test
    fun `known limits still display rounded bounded percentages`() {
        assertEquals(0, ContextUsage(0, 100).toUi().percent)
        assertEquals(62, ContextUsage(615, 1000).toUi().percent)
        assertEquals(100, ContextUsage(Long.MAX_VALUE, 100).toUi().percent)
    }
}
