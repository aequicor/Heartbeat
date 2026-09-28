package io.aequicor.heartbeat.feature.effortconfiguration.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoice
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationEffect
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EffortConfigurationEffectsTest {
    private val choice = EffortChoice(EngineTarget(EngineId("pi"), EngineBindingId("b"), ModelId("m")), "high")

    @Test
    fun `saved choices are loaded back`() = runTest {
        val store = MemoryChoices()
        val machine = RecordingScope()
        val effects = EffortConfigurationEffects(store) { true }

        effects.handle(EffortConfigurationEffect.Save(listOf(choice)), machine)
        effects.handle(EffortConfigurationEffect.Load, machine)

        assertEquals(
            listOf<EffortConfigurationIntent>(EffortConfigurationIntent.Internal.Loaded(listOf(choice))),
            machine.sent,
        )
    }

    @Test
    fun `choices are not persisted while the toggle is off`() = runTest {
        val store = MemoryChoices()
        val machine = RecordingScope()
        EffortConfigurationEffects(store) { true }.handle(EffortConfigurationEffect.Save(listOf(choice)), machine)
        val effects = EffortConfigurationEffects(store) { false }

        effects.handle(EffortConfigurationEffect.Save(emptyList()), machine)
        effects.handle(EffortConfigurationEffect.Load, machine)

        assertEquals(listOf(choice), store.load())
        assertEquals(
            listOf<EffortConfigurationIntent>(EffortConfigurationIntent.Internal.Loaded(emptyList())),
            machine.sent,
        )
    }

    private class MemoryChoices : EffortChoices {
        private var stored = emptyList<EffortChoice>()
        override suspend fun load(): List<EffortChoice> = stored
        override suspend fun save(choices: List<EffortChoice>) {
            stored = choices
        }
    }

    private class RecordingScope : EffectScope<EffortConfigurationIntent> {
        val sent = mutableListOf<EffortConfigurationIntent>()
        override suspend fun send(intent: EffortConfigurationIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
}
