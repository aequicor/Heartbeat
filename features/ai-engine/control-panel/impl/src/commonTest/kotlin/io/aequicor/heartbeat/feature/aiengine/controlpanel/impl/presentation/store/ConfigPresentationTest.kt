package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlFailure
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.FailedOperation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCondition
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFormat
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigSection
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigUnit
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListSyntax
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.TextRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.read
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigPresentationTest {
    private val settings = ConfigDocumentId("settings")
    private val agents = ConfigDocumentId("agents")
    private val schema = EngineConfigSchema(
        documents = listOf(
            ConfigDocumentSpec(settings, text("Settings"), ConfigFormat.Json, ConfigScope.entries.toSet(), "s.json"),
            ConfigDocumentSpec(agents, text("Agents"), ConfigFormat.Text, ConfigScope.entries.toSet(), "AGENTS.md"),
        ),
        pages = listOf(
            page(
                "general",
                ConfigField("offline", text("Offline"), at("offline"), ConfigFieldKind.Flag(isOnByDefault = false)),
                ConfigField("transport", text("Transport"), at("transport"), transport),
                ConfigField("timeout", text("Timeout"), at("retry", "timeout"), timeout),
                ConfigField(
                    "proxy",
                    text("Proxy"),
                    at("httpProxy"),
                    ConfigFieldKind.Text(TextRole.Url),
                    visibleWhen = ConfigCondition(at("offline"), JsonPrimitive(false), isMetWhenAbsent = true),
                ),
                ConfigField("skills", text("Skills"), at("skills"), skills),
                ConfigField("model", text("Model"), at("defaultModel"), ConfigFieldKind.ManagedNote(text("Chat"))),
            ),
            page(
                "project",
                ConfigField(
                    "shared",
                    text("Shared"),
                    at("shared"),
                    ConfigFieldKind.Flag(),
                    scopes = setOf(ConfigScope.Project),
                ),
            ),
            page(
                "instructions",
                ConfigField(
                    "agents",
                    text("Agents"),
                    ConfigBinding.WholeDocument(agents),
                    ConfigFieldKind.Document("md"),
                ),
                ConfigField("env", text("Environment"), at("env"), ConfigFieldKind.KeyValues()),
            ),
        ),
        ownedKeys = listOf(OwnedKey(settings, listOf("defaultModel"), text("Chat"))),
    )
    private val saved = buildJsonObject {
        put("offline", false)
        putJsonObject("retry") { put("timeout", 30) }
        putJsonArray("skills") {
            add("a")
            add("!b")
        }
        put("defaultModel", "x")
    }
    private val config = EngineConfigState(
        EngineId("pi"),
        schema,
        EngineConfigValues(mapOf(settings to ConfigDocument.Json(saved))),
        ConfigRevision(3),
    )

    @Test
    fun `the form shows the visible profile fields with their values and defaults`() {
        val form = config.form(null)

        assertEquals(listOf("general", "instructions"), form.pages.map { it.id })
        val fields = form.fields()
        assertEquals(FieldEditorUi.Flag(false, false), fields.getValue("offline").editor)
        val choice = fields.getValue("transport").editor as FieldEditorUi.Choice
        assertEquals(listOf("\"sse\"", "\"ws\""), choice.options.map { it.key })
        assertEquals(null to "\"sse\"", choice.selected to choice.default)
        assertFalse(fields.getValue("transport").isSet)
        assertEquals(FieldEditorUi.Number("30", null, 0, 600, UnitUi.Seconds), fields.getValue("timeout").editor)
        assertEquals(
            FieldEditorUi.TextList(persistentListOf("a", "!b"), TextRoleUi.Path, isPatterns = true),
            fields.getValue("skills").editor,
        )
        assertEquals(FieldEditorUi.Document("", "md"), fields.getValue("agents").editor)
        assertEquals(FieldEditorUi.Structured(0), fields.getValue("env").editor)
        assertTrue("proxy" in fields)
        assertFalse(form.isDirty)
        assertFalse(form.canSave)
    }

    @Test
    fun `a field is shown only while its condition holds`() {
        val offline = config.edit(null, "offline", FieldInputUi.Flag(true))

        assertFalse("proxy" in config.form(offline).fields())
    }

    @Test
    fun `edits write values and an empty value returns to the default`() {
        var draft = config.edit(null, "transport", FieldInputUi.Choice("\"ws\""))
        draft = config.edit(draft, "timeout", FieldInputUi.Number(" "))
        draft = config.edit(draft, "proxy", FieldInputUi.Text("http://proxy"))
        draft = config.edit(draft, "proxy", FieldInputUi.Text(""))

        val values = draft!!.values
        assertEquals(JsonPrimitive("ws"), values.read(at("transport")))
        assertNull(values.read(at("retry")))
        assertNull(values.read(at("httpProxy")))
        assertEquals(ConfigRevision(3), draft.base)
        assertTrue(config.form(draft).canSave)
    }

    @Test
    fun `a number that does not parse is kept as typed and blocks saving until fixed`() {
        val typed = config.edit(null, "timeout", FieldInputUi.Number("3o"))
        val form = config.form(typed)

        val timeout = form.fields().getValue("timeout")
        assertEquals("3o", (timeout.editor as FieldEditorUi.Number).text)
        assertEquals(listOf(ConfigProblemUi(ConfigProblemReasonUi.NotANumber)), timeout.problems)
        assertEquals(1, form.blockingProblems)
        assertFalse(form.canSave)

        val fixed = config.edit(typed, "timeout", FieldInputUi.Number("45"))
        assertEquals(JsonPrimitive(45), fixed!!.values.read(at("retry", "timeout")))
        assertTrue(config.form(fixed).canSave)
    }

    @Test
    fun `problems belong to their field and an owned key is explained without blocking`() {
        val draft = config.edit(null, "timeout", FieldInputUi.Number("900"))
        val fields = config.form(draft).fields()

        assertEquals(
            listOf(ConfigProblemUi(ConfigProblemReasonUi.OutOfRange)),
            fields.getValue("timeout").problems,
        )
        assertEquals(
            listOf(ConfigProblemUi(ConfigProblemReasonUi.Owned, isBlocking = false)),
            fields.getValue("model").problems,
        )
        assertEquals(1, config.form(draft).blockingProblems)
    }

    @Test
    fun `inputs of another kind and unknown fields change nothing as does editing a draft being saved`() {
        assertEquals(ConfigDraft(config.revision, config.profile), config.edit(null, "offline", FieldInputUi.Text("x")))
        assertNull(config.edit(null, "missing", FieldInputUi.Flag(true)))
        val submitted = config.edit(null, "offline", FieldInputUi.Flag(true))!!.copy(isSubmitted = true)
        assertEquals(submitted, config.edit(submitted, "offline", FieldInputUi.Flag(false)))
    }

    @Test
    fun `blank list items are dropped on save`() {
        val blank = config.edit(null, "skills", FieldInputUi.TextList(persistentListOf("a", " ", "")))
        assertEquals(JsonArray(listOf(JsonPrimitive("a"))), schema.cleaned(blank!!.values).read(at("skills")))

        val empty = config.edit(null, "skills", FieldInputUi.TextList(persistentListOf(" ")))
        assertNull(schema.cleaned(empty!!.values).read(at("skills")))
    }

    @Test
    fun `a submitted draft stays while saved and leaves once the saved revision changes`() {
        val draft = config.edit(null, "offline", FieldInputUi.Flag(true))!!.copy(isSubmitted = true)
        val save = ControlOperation.SaveProfile(EngineId("pi"), ConfigEdit(draft.values), draft.base)
        val conflict = FailedOperation(save, ControlFailure.Config(ConfigFailure.Conflict))
        val newer = config.copy(revision = ConfigRevision(4))

        assertEquals(draft, draft.settle(config, EngineControlState.Active(pending = save)))
        assertEquals(false, draft.settle(config, EngineControlState.Active(failed = conflict))?.isSubmitted)
        assertEquals(draft, draft.settle(config, EngineControlState.Active()))
        assertNull(draft.settle(newer, EngineControlState.Active()))
        assertNull(draft.settle(null, EngineControlState.Active()))
        assertTrue(newer.form(draft.copy(isSubmitted = false)).hasConflict)
    }

    @Test
    fun `every facade value has a panel counterpart`() {
        ConfigProblemReason.entries.forEach { ConfigProblemReasonUi.valueOf(it.name) }
        ConfigUnit.entries.forEach { UnitUi.valueOf(it.name) }
        TextRole.entries.forEach { TextRoleUi.valueOf(it.name) }
    }

    private fun ConfigFormUi.fields(): Map<String, ConfigFieldUi> =
        pages.flatMap { page -> page.sections.flatMap { it.fields } }.associateBy { it.id }

    private fun at(vararg path: String) = ConfigBinding.JsonPath(settings, path.toList())

    private companion object {
        val transport = ConfigFieldKind.Choice(
            listOf(ConfigOption(JsonPrimitive("sse"), text("SSE")), ConfigOption(JsonPrimitive("ws"), text("WS"))),
            default = JsonPrimitive("sse"),
        )
        val timeout = ConfigFieldKind.Number(min = 0, max = 600, unit = ConfigUnit.Seconds)
        val skills = ConfigFieldKind.TextList(TextRole.Path, ListSyntax.ResourcePatterns)

        fun text(value: String) = LocalizedText(value)

        fun page(id: String, vararg fields: ConfigField) =
            ConfigPage(id, text(id), listOf(ConfigSection(id, text(id), fields.toList())))
    }
}
