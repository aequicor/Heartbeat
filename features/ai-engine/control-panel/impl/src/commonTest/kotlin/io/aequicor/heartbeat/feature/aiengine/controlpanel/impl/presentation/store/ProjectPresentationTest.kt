package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlFailure
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.FailedOperation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFormat
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigSection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectPresentationTest {
    private val pi = EngineId("pi")
    private val settings = ConfigDocumentId("settings")
    private val workspace = WorkspaceRef("web")
    private val schema = EngineConfigSchema(
        documents = listOf(
            ConfigDocumentSpec(settings, text("Settings"), ConfigFormat.Json, ConfigScope.entries.toSet(), "s.json"),
        ),
        pages = listOf(
            ConfigPage(
                "main",
                text("Main"),
                listOf(
                    ConfigSection(
                        "main",
                        text("Main"),
                        listOf(
                            ConfigField("retries", text("Retries"), at("retries"), ConfigFieldKind.Number()),
                            ConfigField(
                                "approve",
                                text("Approve"),
                                at("approve"),
                                ConfigFieldKind.Flag(),
                                scopes = setOf(ConfigScope.Project),
                            ),
                            ConfigField(
                                "env",
                                text("Env"),
                                at("env"),
                                ConfigFieldKind.KeyValues(isSecretAllowed = true),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        capabilities = setOf(ConfigCapability.ProjectTrust),
    )
    private val config = EngineConfigState(
        pi,
        schema,
        values(buildJsonObject { put("retries", 2) }),
        ConfigRevision(1),
        trust = mapOf(workspace to ProjectTrust.Trusted),
    )
    private val project = ProjectConfig(
        pi,
        workspace,
        values(
            buildJsonObject {
                put("retries", 5)
                putJsonObject("env") { put("TOKEN", "abc") }
            },
        ),
        ProjectRevision(mapOf(settings to "h1")),
    )

    @Test
    fun `a project shows its fields with the profile values as defaults and its trust`() {
        val project = config.projectForm(project, null, "Web")

        assertEquals(ProjectTrustUi.Trusted, project.trust)
        val fields = project.form.pages.single().sections.single().fields.associateBy { it.id }
        assertEquals(FieldEditorUi.Number("5", 2, null, null, null), fields.getValue("retries").editor)
        assertTrue("approve" in fields)
        assertFalse((fields.getValue("env").editor as FieldEditorUi.KeyValues).isSecretAllowed)
        assertEquals(listOf("settings"), project.form.files.map { it.id })
        assertEquals(
            listOf(ProjectItemUi("web", "Web", ProjectTrustUi.Trusted)),
            config.projectItems(listOf(LocalWorkspace(workspace, "Web"))),
        )
    }

    @Test
    fun `a project draft keeps the files revision and a file that does not parse is written as typed`() {
        var draft = config.editProject(project, null, "retries", FieldInputUi.Number("7"))
        draft = config.editProjectFile(project, draft, "settings", "{ // comment\n}")

        assertEquals(project.revision, draft!!.revision)
        val form = config.projectForm(project, draft, "Web").form
        assertEquals(0, form.blockingProblems)
        assertTrue(form.isSaveable)
        val edit = config.projectSaveOf(project, draft)
        assertEquals(mapOf(settings to "{ // comment\n}"), edit.raw)
    }

    @Test
    fun `a project keeps values in its files and never in the vault`() {
        val secret = FieldInputUi.SetSecret("TOKEN", isSecret = true)
        val draft = config.editProject(project, null, "env", secret)!!

        assertEquals(project.values, draft.draft.values)
        assertEquals(emptyMap(), draft.draft.secrets)
    }

    @Test
    fun `a field of a file that does not parse is edited only in the file editor`() {
        val unreadable = project.copy(values = EngineConfigValues(), unreadable = mapOf(settings to "{ // x }"))

        assertNull(config.editProject(unreadable, null, "retries", FieldInputUi.Number("1")))
        val retries = config.projectForm(unreadable, null, "Web").form.pages.single().sections.single().fields.first()
        assertEquals(
            persistentListOf(ConfigProblemUi(ConfigProblemReasonUi.Unparsed, isBlocking = false)),
            retries.problems,
        )
        assertTrue(config.projectForm(unreadable, null, "Web").form.files.single().isInvalid)
    }

    @Test
    fun `an emptied file that does not parse is removed on save`() {
        val unreadable = project.copy(values = EngineConfigValues(), unreadable = mapOf(settings to "{ // x }"))
        val draft = config.editProjectFile(unreadable, null, "settings", "")!!

        assertEquals(mapOf(settings to ""), config.projectSaveOf(unreadable, draft).raw)
    }

    @Test
    fun `a submitted project draft stays until its save succeeds even when the files do not change`() {
        val draft = config.editProject(project, null, "retries", FieldInputUi.Number("7"))!!
            .let { it.copy(draft = it.draft.copy(isSubmitted = true), submittedAfterVersion = 4) }
        val save = ControlOperation.SaveProject(pi, workspace, ProjectEdit(draft.draft.values), draft.revision)
        val failed = FailedOperation(save, ControlFailure.Config(ConfigFailure.Conflict))
        val reread = project.copy(revision = ProjectRevision(mapOf(settings to "h2")))

        assertEquals(draft, draft.settle(project, EngineControlState.Active(pending = save)))
        assertEquals(false, draft.settle(project, EngineControlState.Active(failed = failed))?.draft?.isSubmitted)
        assertEquals(draft, draft.settle(reread, EngineControlState.Active(projectSaveVersion = 4)))
        assertNull(draft.settle(project, EngineControlState.Active(projectSaveVersion = 5)))
        assertNull(draft.settle(reread, EngineControlState.Active(projectSaveVersion = 5)))
        assertNull(draft.settle(project.copy(workspace = WorkspaceRef("other")), EngineControlState.Active()))
        val edited = draft.copy(draft = draft.draft.copy(isSubmitted = false))
        assertTrue(config.projectForm(reread, edited, "Web").form.hasConflict)
    }

    @Test
    fun `an engine that configures nothing in projects lists no projects`() {
        val profileOnly = config.copy(
            schema = schema.copy(documents = schema.documents.map { it.copy(scopes = setOf(ConfigScope.Profile)) }),
        )
        assertEquals(emptyList(), profileOnly.projectItems(listOf(LocalWorkspace(workspace, "Web"))))
    }

    private fun at(vararg path: String) = ConfigBinding.JsonPath(settings, path.toList())

    private fun values(document: JsonObject) = EngineConfigValues(mapOf(settings to ConfigDocument.Json(document)))

    private companion object {
        fun text(value: String) = LocalizedText(value)
    }
}
