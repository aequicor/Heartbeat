package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl

import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ControlTestData.codex
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ControlTestData.pi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineStatusUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.listState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.panelState
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineControlProjectionTest {
    private val management = EngineManagementState(
        isEnabled = true,
        engines = listOf(
            ControlTestData.managed(pi, "Pi"),
            ControlTestData.managed(codex, "Codex", setOf(DisabledReason.DisabledByUser), version = null),
            ControlTestData.managed(EngineId("claude"), "Claude", setOf(DisabledReason.UnsupportedPlatform)),
        ),
    )
    private val observed = EngineControlState.Active(
        snapshot = ControlSnapshot(management, ControlTestData.configuration(pi)),
    )

    @Test
    fun `the list shows every engine with its status version and whether it is configurable`() {
        val list = listState(observed)

        assertEquals(listOf("Pi", "Codex", "Claude"), list.engines.map { it.title })
        assertEquals(
            listOf(EngineStatusUi.On, EngineStatusUi.Off, EngineStatusUi.Unavailable),
            list.engines.map { it.status },
        )
        assertEquals(listOf("1.0.0", null, "1.0.0"), list.engines.map { it.version })
        assertEquals(listOf(true, false, false), list.engines.map { it.isConfigurable })
        assertEquals(false, list.isLoading)
    }

    @Test
    fun `the list waits for the first snapshot and reports a failed one`() {
        assertTrue(listState(EngineControlState.Idle).isLoading)
        assertTrue(listState(EngineControlState.Active()).isLoading)
        assertTrue(listState(EngineControlState.Active(loadFailure = EngineFailure.Unknown())).hasLoadFailure)
        val off = EngineControlState.Active(
            snapshot = ControlSnapshot(EngineManagementState.Off, EngineConfigurationState.Off),
        )
        assertTrue(listState(off).isOff)
    }

    @Test
    fun `the panel shows the engine and its configuration pages`() {
        val panel = panelState(pi, observed)

        assertEquals("Pi", panel.title)
        assertEquals(listOf("network"), panel.pages.map { it.id })
        assertEquals("Сеть", panel.pages.single().title.resolve("ru"))
        assertEquals(false, panel.isConfigurationOff)
        assertEquals(emptyList(), panelState(codex, observed).pages.map { it.id })
    }

    @Test
    fun `the panel notices a missing engine and configuration that is off`() {
        assertTrue(panelState(EngineId("gone"), observed).isMissing)
        val off = observed.copy(snapshot = ControlSnapshot(management, EngineConfigurationState.Off))
        assertTrue(panelState(pi, off).isConfigurationOff)
    }
}
