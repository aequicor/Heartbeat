package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ControlTestData
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ControlTestData.codex
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineControlPanelModelTest {
    private val management =
        EngineManagementState(isEnabled = true, engines = listOf(ControlTestData.managed(codex, "Codex")))
    private val machine = FakeMachine<EngineControlState, EngineControlIntent, EngineControlOutput>(
        EngineControlState.Active(snapshot = ControlSnapshot(management, EngineConfigurationState.Off)),
    )

    private fun TestScope.model() =
        EngineControlPanelModel(machine, EngineControlTarget(codex), testScopeHandle(), testStoreFactory())

    @Test
    fun `the panel starts the machine and shows the engine`() = runTest {
        val screen = subscribe(model().store)
        runCurrent()
        assertEquals(EngineControlIntent.Public.Start, machine.sent.first())
        assertEquals("Codex", screen.states.value.title)
    }

    @Test
    fun `retry and dismiss reach the machine`() = runTest {
        val model = model()
        subscribe(model.store)
        model.store.intent(EngineControlPanelIntent.RetryLoad)
        model.store.intent(EngineControlPanelIntent.DismissError)
        runCurrent()
        assertEquals(
            listOf(EngineControlIntent.Public.RetryLoad, EngineControlIntent.Public.DismissError),
            machine.sent.drop(1),
        )
    }
}
