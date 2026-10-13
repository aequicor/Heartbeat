package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionStrategy
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StoredSettingsTest {
    @Test
    fun `stored settings round trip`() {
        val settings = ContextOptimizationSettings(thresholdPercent = 60, strategy = CompactionStrategy.Native)

        assertEquals(settings, StoredSettings(settings).toSettings())
    }

    @Test
    fun `values outside the allowed ranges are brought into them the others are kept`() {
        val json = """
            {"thresholdPercent": 20, "koogKeepRecentTurns": 99, "koogToolStubChars": -1,
             "offloadThresholdChars": 1, "strategy": "Rollover", "isToolCallsCollapsed": true}
        """.trimIndent()

        val settings = Json.decodeFromString(StoredSettings.serializer(), json).toSettings()

        assertEquals(
            ContextOptimizationSettings(
                thresholdPercent = ContextOptimizationSettings.THRESHOLD_RANGE.first,
                strategy = CompactionStrategy.Rollover,
                koogKeepRecentTurns = ContextOptimizationSettings.KEEP_RECENT_TURNS_RANGE.last,
                koogToolStubChars = ContextOptimizationSettings.TOOL_STUB_CHARS_RANGE.first,
                offloadThresholdChars = ContextOptimizationSettings.OFFLOAD_THRESHOLD_RANGE.first,
                isToolCallsCollapsed = true,
            ),
            settings,
        )
    }
}
