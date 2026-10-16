package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ControlTestData
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineControlPanelModelTest {
    private val machine = FakeMachine<EngineControlState, EngineControlIntent, EngineControlOutput>(managedSnapshot())

    private fun TestScope.model() =
        EngineControlPanelModel(machine, EngineControlTarget(CodexId), testScopeHandle(), testStoreFactory())

    @Test
    fun `the panel starts the machine and shows the engine`() = runTest {
        val screen = subscribe(model().store)
        runCurrent()
        assertEquals(EngineControlIntent.Public.Start, machine.sent.first())
        assertEquals("Codex", screen.states.value.title)
    }

    @Test
    fun `a saved launch draft is sent once and the saved settings are shown again`() = runTest {
        val model = model()
        val screen = subscribe(model.store)
        val draft = LaunchDraftUi(executable = "/opt/codex/bin/codex")
        model.store.intent(EngineManagementIntent.EditLaunch(draft))
        runCurrent()
        assertEquals(draft, screen.states.value.panel?.launch?.draft)

        model.store.intent(EngineManagementIntent.SaveLaunch)
        runCurrent()

        val configure = EngineCommand.Configure(LaunchSettings(executable = "/opt/codex/bin/codex"))
        assertEquals(
            listOf(EngineControlIntent.Public.Apply(ControlOperation.Manage(CodexId, configure))),
            machine.sent.filterIsInstance<EngineControlIntent.Public.Apply>(),
        )
        assertEquals(null, screen.states.value.launchDraft)
    }

    @Test
    fun `a request the machine refuses keeps its local input`() = runTest {
        val model = model()
        val screen = subscribe(model.store)
        machine.result = SendResult.NotRunning
        val draft = LaunchDraftUi(executable = "/opt/codex/bin/codex")
        model.store.intent(EngineManagementIntent.EditLaunch(draft))
        model.store.intent(EngineManagementIntent.SaveLaunch)
        runCurrent()

        assertEquals(draft, screen.states.value.launchDraft)
    }

    @Test
    fun `an uninstall waits for confirmation and a dismissed one is forgotten`() = runTest {
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(EngineManagementIntent.RequestEngineAction(EngineActionUi.Uninstall))
        runCurrent()
        assertEquals(EngineActionUi.Uninstall, screen.states.value.confirmAction)
        assertTrue(machine.sent.none { it is EngineControlIntent.Public.Apply })

        model.store.intent(EngineManagementIntent.ConfirmEngineAction)
        model.store.intent(EngineManagementIntent.RequestEngineAction(EngineActionUi.Logout))
        model.store.intent(EngineManagementIntent.DismissEngineAction)
        runCurrent()

        val uninstall = EngineCommand.Start(EngineAction.Uninstall)
        assertEquals(
            listOf(EngineControlIntent.Public.Apply(ControlOperation.Manage(CodexId, uninstall))),
            machine.sent.filterIsInstance<EngineControlIntent.Public.Apply>(),
        )
        assertEquals(null, screen.states.value.confirmAction)
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

    @Test
    fun `pages open and close and an unknown page stays closed`() = runTest {
        machine.state.value = configured(ConfigRevision(0))
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(ConfigIntent.OpenPage("missing"))
        runCurrent()
        assertEquals(null, screen.states.value.openPage)

        model.store.intent(ConfigIntent.OpenPage("network"))
        runCurrent()
        assertEquals("network", screen.states.value.openPage)
        model.store.intent(ConfigIntent.ClosePage)
        runCurrent()
        assertEquals(null, screen.states.value.openPage)
    }

    @Test
    fun `an edited configuration is saved against the revision it was read at`() = runTest {
        machine.state.value = configured(ConfigRevision(2))
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(ConfigIntent.EditField("retries", FieldInputUi.Number("3")))
        runCurrent()
        assertEquals(true, screen.states.value.config?.canSave)

        model.store.intent(ConfigIntent.SaveConfig)
        runCurrent()

        val values = EngineConfigValues(
            mapOf(ControlTestData.settings to ConfigDocument.Json(buildJsonObject { put("retry", 3) })),
        )
        val save = ControlOperation.SaveProfile(CodexId, ConfigEdit(values), ConfigRevision(2))
        assertEquals(
            listOf(EngineControlIntent.Public.Apply(save)),
            machine.sent.filterIsInstance<EngineControlIntent.Public.Apply>(),
        )
        assertEquals(true, screen.states.value.config?.isSubmitted)

        machine.state.value = configured(ConfigRevision(3), values)
        runCurrent()
        assertEquals(null, screen.states.value.configDraft)
        assertEquals(false, screen.states.value.config?.isDirty)
        val retries = screen.states.value.config?.pages?.single()?.sections?.single()?.fields?.single()
        assertEquals(FieldEditorUi.Number("3", null, 0, null, null), retries?.editor)
    }

    @Test
    fun `a discarded draft shows the saved configuration again`() = runTest {
        machine.state.value = configured(ConfigRevision(0))
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(ConfigIntent.EditField("retries", FieldInputUi.Number("3")))
        model.store.intent(ConfigIntent.DiscardConfig)
        runCurrent()

        assertEquals(null, screen.states.value.configDraft)
        assertEquals(false, screen.states.value.config?.isDirty)
    }

    private fun configured(revision: ConfigRevision, values: EngineConfigValues = EngineConfigValues()) =
        (managedSnapshot() as EngineControlState.Active).let { active ->
            val config = EngineConfigState(CodexId, ControlTestData.schema, values, revision)
            active.copy(
                snapshot = active.snapshot?.copy(configuration = EngineConfigurationState(true, listOf(config))),
            )
        }
}
