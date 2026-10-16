package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ControlTestData
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceInventory
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pro.respawn.flowmvi.test.subscribeAndTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineControlPanelModelTest {
    private val machine = FakeMachine<EngineControlState, EngineControlIntent, EngineControlOutput>(managedSnapshot())

    private fun TestScope.model() =
        EngineControlPanelModel(machine, EngineControlTarget(CodexId), testScopeHandle(), testStoreFactory())

    @Test
    fun `opening a resource page reads saved inventory and filters other scopes`() = runTest {
        val active = configured(ConfigRevision(0))
        val snapshot = requireNotNull(active.snapshot)
        val configuration = snapshot.configuration.copy(
            engines = snapshot.configuration.engines.map {
                it.copy(schema = it.schema.copy(pages = it.schema.pages.map { page -> page.copy(hasResources = true) }))
            },
        )
        machine.state.value = active.copy(snapshot = snapshot.copy(configuration = configuration))
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(ConfigIntent.OpenPage("network"))
        runCurrent()
        assertEquals(
            EngineControlIntent.Public.Apply(ControlOperation.ListResources(CodexId)),
            machine.sent.last(),
        )
        val inventory = ResourceInventory(CodexId, null, emptyList())
        machine.state.value = (machine.state.value as EngineControlState.Active).copy(resources = inventory)
        runCurrent()
        assertEquals(0, screen.states.value.resources?.files?.size)
        machine.state.value = (machine.state.value as EngineControlState.Active).copy(
            resources = inventory.copy(workspace = WorkspaceRef("other")),
        )
        runCurrent()
        assertEquals(null, screen.states.value.resources)
    }

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
        assertEquals(true, screen.states.value.config?.isSaveable)

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

    @Test
    fun `a file editor opens as a page and leaving it shows the parsed document again`() = runTest {
        machine.state.value = configured(ConfigRevision(0))
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(ConfigIntent.OpenPage(filePage("settings")))
        model.store.intent(ConfigIntent.EditFile("settings", "{\"retry\": 4}"))
        runCurrent()
        assertEquals(filePage("settings"), screen.states.value.openPage)
        assertEquals("{\"retry\": 4}", screen.states.value.config?.files?.single()?.text)

        model.store.intent(ConfigIntent.ClosePage)
        runCurrent()
        assertEquals(null, screen.states.value.openPage)
        assertEquals("{\n    \"retry\": 4\n}", screen.states.value.config?.files?.single()?.text)
    }

    @Test
    fun `a project opens through the machine and closes with it`() = runTest {
        machine.state.value = configured(ConfigRevision(0))
        val model = model()
        val screen = subscribe(model.store)
        val web = WorkspaceRef("web")
        model.store.intent(ProjectIntent.Open("web"))
        runCurrent()
        assertEquals(EngineControlIntent.Public.Apply(ControlOperation.OpenProject(CodexId, web)), machine.sent.last())
        assertEquals("web" to null, screen.states.value.openProject to screen.states.value.project)

        val project = ProjectConfig(CodexId, web, EngineConfigValues(), ProjectRevision())
        machine.state.value = (configured(ConfigRevision(0)) as EngineControlState.Active).copy(project = project)
        runCurrent()
        assertEquals("web", screen.states.value.project?.id)

        model.store.intent(ProjectIntent.SetTrust(ProjectTrustUi.Trusted))
        model.store.intent(ProjectIntent.Close)
        runCurrent()
        assertEquals(
            listOf(
                EngineControlIntent.Public.Apply(ControlOperation.SetTrust(CodexId, web, ProjectTrust.Trusted)),
                EngineControlIntent.Public.CloseProject,
            ),
            machine.sent.takeLast(2),
        )
        assertEquals(null, screen.states.value.openProject)
    }

    @Test
    fun `an import is previewed chosen and run against the saved revision`() = runTest {
        machine.state.value = configured(ConfigRevision(3), capabilities = setOf(ConfigCapability.ImportNative))
        val model = model()
        val screen = subscribe(model.store)
        assertEquals(true, screen.states.value.isImportable)
        model.store.intent(ImportIntent.Preview)
        runCurrent()
        assertEquals(EngineControlIntent.Public.Apply(ControlOperation.PreviewImport(CodexId)), machine.sent.last())

        val preview = ImportPreview("~/.pi/agent", listOf(ImportItem("settings", LocalizedText("Settings"))))
        machine.state.value = (machine.state.value as EngineControlState.Active).copy(importPreview = preview)
        model.store.intent(ImportIntent.SetReplacing(true))
        model.store.intent(ImportIntent.Import)
        runCurrent()
        val selection = ImportSelection(setOf("settings"), ImportMode.Replace)
        assertEquals(
            EngineControlIntent.Public.Apply(ControlOperation.Import(CodexId, selection, ConfigRevision(3))),
            machine.sent.last(),
        )

        model.store.intent(ImportIntent.Dismiss)
        runCurrent()
        assertEquals(EngineControlIntent.Public.DismissImport, machine.sent.last())
        assertEquals(null, screen.states.value.importing)
    }

    @Test
    fun `back closes an open page first and then leaves the panel`() = runTest {
        machine.state.value = configured(ConfigRevision(0))
        val model = model()
        val actions = mutableListOf<EngineControlPanelAction>()
        val screen = subscribe(model.store, actions)
        model.store.intent(ConfigIntent.OpenPage("network"))
        model.store.intent(EngineControlPanelIntent.Back)
        runCurrent()
        assertEquals(null, screen.states.value.openPage)
        assertTrue(actions.isEmpty())

        model.store.intent(EngineControlPanelIntent.Back)
        runCurrent()
        assertEquals(listOf<EngineControlPanelAction>(EngineControlPanelAction.Leave), actions)
    }

    @Test
    fun `unsaved changes ask before the panel is left and are dropped only when discarded`() = runTest {
        machine.state.value = configured(ConfigRevision(0))
        val model = model()
        val actions = mutableListOf<EngineControlPanelAction>()
        val screen = subscribe(model.store, actions)
        model.store.intent(ConfigIntent.EditField("retries", FieldInputUi.Number("3")))
        model.store.intent(EngineControlPanelIntent.Back)
        runCurrent()
        assertTrue(screen.states.value.isLeaving)
        assertTrue(actions.isEmpty())

        model.store.intent(EngineControlPanelIntent.KeepEditing)
        runCurrent()
        assertEquals(false, screen.states.value.isLeaving)
        assertEquals(true, screen.states.value.config?.isDirty)

        model.store.intent(EngineControlPanelIntent.Back)
        model.store.intent(EngineControlPanelIntent.DiscardAndLeave)
        runCurrent()
        assertEquals(null, screen.states.value.configDraft)
        assertEquals(listOf<EngineControlPanelAction>(EngineControlPanelAction.Leave), actions)
    }

    @Test
    fun `a project save with unchanged files clears the submitted draft`() = runTest {
        val values = EngineConfigValues(mapOf(ControlTestData.settings to ConfigDocument.Json(buildJsonObject { })))
        val project = ProjectConfig(CodexId, WorkspaceRef("web"), values, ProjectRevision())
        machine.state.value = (configured(ConfigRevision(0)) as EngineControlState.Active).copy(project = project)
        val model = model()
        model.store.closeAndWait()
        model.store.subscribeAndTest {
            model.store.intent(ProjectIntent.Open("web"))
            // Formatting an empty JSON object changes the raw draft but need not change the saved file.
            model.store.intent(ProjectIntent.EditFile("settings", "{}"))
            runCurrent()
            assertTrue(states.value.project!!.form.isSaveable)
            model.store.intent(ProjectIntent.Save)
            runCurrent()
            assertTrue(states.value.project!!.form.isSubmitted)

            machine.state.value = (machine.state.value as EngineControlState.Active).copy(projectSaveVersion = 1)
            runCurrent()
            assertNull(states.value.projectDraft)
            assertFalse(states.value.project!!.form.isSubmitted)
            assertFalse(states.value.project!!.form.isDirty)
        }
    }

    @Test
    fun `a project save completed before send returns clears its draft`() = runTest {
        val values = EngineConfigValues(mapOf(ControlTestData.settings to ConfigDocument.Json(buildJsonObject { })))
        val project = ProjectConfig(CodexId, WorkspaceRef("web"), values, ProjectRevision())
        machine.state.value = (configured(ConfigRevision(0)) as EngineControlState.Active).copy(project = project)
        machine.onSend = { intent ->
            if ((intent as? EngineControlIntent.Public.Apply)?.operation is ControlOperation.SaveProject) {
                val active = machine.state.value as EngineControlState.Active
                machine.state.value = active.copy(projectSaveVersion = active.projectSaveVersion + 1)
            }
        }
        val model = model()
        model.store.closeAndWait()
        model.store.subscribeAndTest {
            model.store.intent(ProjectIntent.Open("web"))
            model.store.intent(ProjectIntent.EditFile("settings", "{}"))
            model.store.intent(ProjectIntent.Save)
            runCurrent()

            assertNull(states.value.projectDraft)
            assertFalse(states.value.project!!.form.isSubmitted)
            model.store.intent(ProjectIntent.EditField("retries", FieldInputUi.Number("3")))
            runCurrent()
            assertTrue(states.value.project!!.form.isSaveable)
        }
    }

    @Test
    fun `back from an emptied unreadable project file keeps its deletion for save`() = runTest {
        val project = ProjectConfig(
            CodexId,
            WorkspaceRef("web"),
            EngineConfigValues(),
            ProjectRevision(mapOf(ControlTestData.settings to "broken")),
            unreadable = mapOf(ControlTestData.settings to "{broken"),
        )
        machine.state.value = (configured(ConfigRevision(0)) as EngineControlState.Active).copy(project = project)
        val model = model()
        model.store.closeAndWait()
        model.store.subscribeAndTest {
            model.store.intent(ProjectIntent.Open("web"))
            model.store.intent(ProjectIntent.OpenFile("settings"))
            model.store.intent(ProjectIntent.EditFile("settings", ""))
            model.store.intent(EngineControlPanelIntent.Back)
            runCurrent()

            assertNull(states.value.projectFile)
            assertTrue(states.value.project!!.form.isSaveable)
            assertEquals("", states.value.project!!.form.files.single().text)
            model.store.intent(ProjectIntent.Save)
            runCurrent()
            val operation = assertIs<EngineControlIntent.Public.Apply>(machine.sent.last()).operation
            val save = assertIs<ControlOperation.SaveProject>(operation)
            assertEquals(mapOf(ControlTestData.settings to ""), save.edit.raw)
        }
    }

    private fun configured(
        revision: ConfigRevision,
        values: EngineConfigValues = EngineConfigValues(),
        capabilities: Set<ConfigCapability> = emptySet(),
    ) = (managedSnapshot() as EngineControlState.Active).let { active ->
        val schema = ControlTestData.schema.copy(capabilities = capabilities)
        val config = EngineConfigState(CodexId, schema, values, revision)
        active.copy(
            snapshot = active.snapshot?.copy(configuration = EngineConfigurationState(true, listOf(config))),
        )
    }
}
