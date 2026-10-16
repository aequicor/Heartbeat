package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsOutput
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.KoogId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.DescribedModel
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineConnectionsDescriptionsTest {
    private val machine = FakeMachine<EngineConnectionsState, EngineConnectionsIntent, EngineConnectionsOutput>(
        EngineConnectionsState.Active(settingsSnapshot()),
    )
    private val described = MutableStateFlow<Set<DescribedModel>?>(null)

    @Test
    fun `models show whether they are described only while descriptions are on`() = runTest {
        val model = EngineConnectionsModel(machine, testScopeHandle(), testStoreFactory()) { described }
        val screen = subscribe(model.store)
        assertEquals(listOf(null, null), screen.states.value.descriptions())

        described.value = setOf(DescribedModel(KoogId, ModelId("qwen")))
        runCurrent()
        assertEquals(
            listOf(ModelDescriptionUi.Missing, ModelDescriptionUi.Described),
            screen.states.value.descriptions(),
        )

        described.value = null
        runCurrent()
        assertEquals(listOf(null, null), screen.states.value.descriptions())
    }

    private fun EngineConnectionsScreenState.descriptions() = models?.models.orEmpty().map { it.description }
}
