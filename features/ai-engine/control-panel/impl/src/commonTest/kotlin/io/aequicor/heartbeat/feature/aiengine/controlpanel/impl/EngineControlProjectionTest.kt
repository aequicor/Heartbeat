package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl

import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlFailure
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.FailedOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ControlTestData.codex
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ControlTestData.pi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineStatusUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.listState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.reflect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
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
        val panel = EngineControlPanelState("x").reflect(pi, observed)

        assertEquals("Pi", panel.title)
        assertEquals(listOf("network"), panel.pages.map { it.id })
        assertEquals("Сеть", panel.pages.single().title.resolve("ru"))
        assertEquals(false, panel.isConfigurationOff)
        assertEquals(emptyList(), EngineControlPanelState("x").reflect(codex, observed).pages.map { it.id })
    }

    @Test
    fun `a restarting machine keeps what the panel showed while it loads`() {
        val restarting = EngineControlPanelState("x").reflect(pi, observed).reflect(pi, EngineControlState.Idle)

        assertTrue(restarting.isLoading)
        assertEquals("Pi", restarting.title)
        assertEquals(listOf("network"), restarting.pages.map { it.id })
    }

    @Test
    fun `the panel notices a missing engine and configuration that is off`() {
        assertTrue(EngineControlPanelState("x").reflect(EngineId("gone"), observed).isMissing)
        val off = observed.copy(snapshot = ControlSnapshot(management, EngineConfigurationState.Off))
        assertTrue(EngineControlPanelState("x").reflect(pi, off).isConfigurationOff)
    }

    @Test
    fun `the panel shows the failure of the last operation`() {
        val restart = ControlOperation.Manage(pi, EngineCommand.Restart)
        val network = ControlFailure.Engine(EngineFailure.Transport(TransportFailureReason.Timeout))
        val failed = observed.copy(failed = FailedOperation(restart, network))
        assertEquals(FailureUi.Network, EngineControlPanelState("x").reflect(pi, failed).failure)

        val config = observed.copy(failed = FailedOperation(restart, ControlFailure.Config(ConfigFailure.Conflict)))
        assertEquals(FailureUi.Unknown, EngineControlPanelState("x").reflect(pi, config).failure)
        assertEquals(null, EngineControlPanelState("x").reflect(pi, observed).failure)
    }
}
