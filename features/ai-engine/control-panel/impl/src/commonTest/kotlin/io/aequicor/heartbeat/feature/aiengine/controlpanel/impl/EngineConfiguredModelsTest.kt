package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.connections.api.BindingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.domain.configuredEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineConfiguredModelsTest {
    @Test
    fun `only enabled bindings with selected models configure an engine`() = runTest {
        val first = EngineBinding(EngineBindingId("first"), EngineId("engine"), AuthSourceId("source"))
        val second = first.copy(id = EngineBindingId("second"))
        val other = first.copy(id = EngineBindingId("other"), engine = EngineId("other-engine"))
        val selected = ConfiguredSelections(
            ModelSelection(listOf(BindingModels(first.id, setOf(ModelId("model"))))),
        )
        val bindings = MutableStateFlow(listOf(first, second, other))
        val configured = selected.configuredEngines(bindings)
        assertEquals(setOf(first.engine), configured.first())

        bindings.value = listOf(first.copy(isEnabled = false), second, other)
        assertEquals(emptySet(), configured.first())
        selected.state.value = selected.state.value.withEnabled(second.id, setOf(ModelId("second-model")))
        assertEquals(setOf(first.engine), configured.first())

        bindings.value = listOf(other)
        assertEquals(emptySet(), configured.first())
        assertEquals(setOf(ModelId("model")), selected.state.value.enabled(first.id))
        selected.state.value = selected.state.value.withEnabled(other.id, setOf(ModelId("other-model")))
        assertEquals(setOf(other.engine), configured.first())
    }
}

private class ConfiguredSelections(initial: ModelSelection) : ModelSelections {
    val state = MutableStateFlow(initial)
    override fun observe() = state
    override suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection = error("Read only")
}
