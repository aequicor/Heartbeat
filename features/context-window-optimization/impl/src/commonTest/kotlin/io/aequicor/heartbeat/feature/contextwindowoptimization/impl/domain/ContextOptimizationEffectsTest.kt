package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEffect
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextOptimizationEffectsTest {
    private val store = MemoryStore()
    private val scope = RecordingScope()
    private val effects = ContextOptimizationEffects(store)

    @Test
    fun `load reports stored settings or their absence`() = runTest {
        effects.handle(ContextOptimizationEffect.Load, scope)
        store.value = ContextOptimizationSettings(thresholdPercent = 60)
        effects.handle(ContextOptimizationEffect.Load, scope)

        assertEquals<List<ContextOptimizationIntent>>(
            listOf(
                ContextOptimizationIntent.Internal.Loaded(null),
                ContextOptimizationIntent.Internal.Loaded(ContextOptimizationSettings(thresholdPercent = 60)),
            ),
            scope.sent,
        )
    }

    @Test
    fun `a save older than the last written revision is dropped`() = runTest {
        val newer = ContextOptimizationSettings(thresholdPercent = 90)
        effects.handle(ContextOptimizationEffect.Save(newer, revision = 2), scope)
        effects.handle(
            ContextOptimizationEffect.Save(ContextOptimizationSettings(thresholdPercent = 70), revision = 1),
            scope,
        )

        assertEquals(newer, store.value)
        assertEquals(1, store.saves)
    }

    private class MemoryStore : ContextSettingsStore {
        var value: ContextOptimizationSettings? = null
        var saves = 0

        override suspend fun load() = value

        override suspend fun save(settings: ContextOptimizationSettings) {
            saves++
            value = settings
        }
    }

    private class RecordingScope : EffectScope<ContextOptimizationIntent> {
        val sent = mutableListOf<ContextOptimizationIntent>()

        override suspend fun send(intent: ContextOptimizationIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
}
