package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentSpec
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntriesShape
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntryField
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntryMatch
import io.aequicor.heartbeat.feature.aiengine.facade.api.EntryVariant
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.TextRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.read
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretReference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigEntriesTest {
    private val mcp = ConfigDocumentId("mcp")
    private val servers = ConfigBinding.JsonPath(mcp, listOf("servers"))
    private val packages = ConfigBinding.JsonPath(mcp, listOf("packages"))
    private val headers = ConfigBinding.JsonPath(mcp, listOf("headers"))
    private val schema = EngineConfigSchema(
        documents = listOf(
            ConfigDocumentSpec(mcp, text("MCP"), ConfigFormat.Json, setOf(ConfigScope.Profile), "m.json"),
        ),
        pages = listOf(
            ConfigPage(
                "mcp",
                text("MCP"),
                listOf(
                    ConfigSection(
                        "main",
                        text("Main"),
                        listOf(
                            ConfigField("servers", text("Servers"), servers, serverKind),
                            ConfigField("packages", text("Packages"), packages, packageKind),
                            ConfigField(
                                "headers",
                                text("Headers"),
                                headers,
                                ConfigFieldKind.KeyValues(keyPattern = "[A-Z_]+"),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `entries show each item with its variant fields and saved secrets`() {
        val form = config(
            buildJsonObject {
                putJsonObject("servers") {
                    putJsonObject("github") {
                        put("command", "npx")
                        putJsonObject("env") { put("TOKEN", secretReference("GH")) }
                    }
                    putJsonObject("odd") { put("port", 1) }
                }
            },
            saved = mapOf("GH" to 1L),
        ).form(null)

        val editor = form.field("servers").editor as FieldEditorUi.Entries
        assertEquals(listOf("github", "odd"), editor.items.map { it.key })
        val github = editor.items[0]
        assertEquals("stdio", github.variant)
        assertEquals(FieldEditorUi.Text("npx", TextRoleUi.Command, null), github.field("command").editor)
        assertEquals(
            persistentListOf(KeyValueRowUi("TOKEN", "", "GH", SecretUi.Saved)),
            (github.field("env").editor as FieldEditorUi.KeyValues).rows,
        )
        assertEquals(null, editor.items[1].variant)
        assertEquals(listOf(ConfigProblemReasonUi.UnknownVariant), editor.items[1].problems.map { it.reason })
    }

    @Test
    fun `named entries are added renamed edited and removed keeping their form`() {
        val config = config(JsonObject(emptyMap()))
        var draft = config.edit(null, "servers", FieldInputUi.AddEntry("stdio"))
        draft = config.edit(draft, "servers", FieldInputUi.AddEntry("stdio"))
        draft = config.edit(draft, "servers", FieldInputUi.RenameEntry("stdio", "files"))
        draft = config.edit(draft, "servers", FieldInputUi.RenameEntry("files", "stdio-2"))
        draft = config.edit(draft, "servers", FieldInputUi.EditEntry("files", "command", FieldInputUi.Text("npx")))
        draft = config.edit(draft, "servers", FieldInputUi.EditEntry("stdio-2", "command", FieldInputUi.Text("")))
        draft = config.edit(draft, "servers", FieldInputUi.AddEntry("http"))
        draft = config.edit(draft, "servers", FieldInputUi.RemoveEntry("http"))

        assertEquals(
            buildJsonObject {
                putJsonObject("files") { put("command", "npx") }
                putJsonObject("stdio-2") { put("command", "") }
            },
            draft!!.values.read(servers),
        )
    }

    @Test
    fun `a list keeps primitive and object items`() {
        val config = config(JsonObject(emptyMap()))
        var draft = config.edit(null, "packages", FieldInputUi.AddEntry("source"))
        draft = config.edit(draft, "packages", FieldInputUi.AddEntry("filtered"))
        draft = config.edit(draft, "packages", FieldInputUi.EditEntry("0", "source", FieldInputUi.Text("npm:x")))

        assertEquals(
            JsonArray(listOf(JsonPrimitive("npm:x"), buildJsonObject { put("source", "") })),
            draft!!.values.read(packages),
        )
    }

    @Test
    fun `a value moved into the vault is typed for a new slot and saved with the profile`() {
        val config = config(
            buildJsonObject {
                putJsonObject("servers") {
                    putJsonObject("github") {
                        put("command", "npx")
                        putJsonObject("env") { put("TOKEN", "abc") }
                    }
                }
            },
        )
        val secret = FieldInputUi.EditEntry("github", "env", FieldInputUi.SetSecret("TOKEN", isSecret = true))
        val draft = config.edit(null, "servers", secret)!!

        val slot = "SERVERS_GITHUB_ENV_TOKEN"
        assertEquals(mapOf(slot to "abc"), draft.secrets)
        val env = (config.form(draft).field("servers").editor as FieldEditorUi.Entries).items.single().field("env")
        assertEquals(
            KeyValueRowUi("TOKEN", "abc", slot, SecretUi.Typed),
            (env.editor as FieldEditorUi.KeyValues).rows.single(),
        )
        assertTrue(env.problems.isEmpty())
        val edit = schema.saveOf(draft)
        assertEquals(setOf(slot), edit.secrets.keys)
        assertEquals("abc", edit.secrets.getValue(slot)!!.reveal { String(it) })

        val back = FieldInputUi.EditEntry("github", "env", FieldInputUi.SetSecret("TOKEN", isSecret = false))
        val file = config.edit(draft, "servers", back)!!
        assertEquals(emptyMap(), file.secrets)
        assertEquals(JsonPrimitive(""), (file.values.read(servers) as JsonObject).nested("github", "env", "TOKEN"))
    }

    @Test
    fun `a replacement typed for a saved secret keeps the reference`() {
        val config = config(
            buildJsonObject { putJsonObject("headers") { put("AUTH", secretReference("GH")) } },
            saved = mapOf("GH" to 1L),
        )
        val typed = config.edit(null, "headers", pairs(KeyValueRowUi("AUTH", "new", "GH", SecretUi.Saved)))!!
        assertEquals(mapOf("GH" to "new"), typed.secrets)
        assertEquals(JsonPrimitive(secretReference("GH")), (typed.values.read(headers) as JsonObject)["AUTH"])

        val cleared = config.edit(typed, "headers", pairs(KeyValueRowUi("AUTH", "", "GH", SecretUi.Typed)))!!
        assertEquals(emptyMap(), cleared.secrets)
    }

    @Test
    fun `blank names being typed do not block saving and are dropped on save`() {
        val config = config(buildJsonObject { putJsonObject("headers") { put("AUTH", "x") } })
        val draft = config.edit(null, "headers", pairs(KeyValueRowUi("AUTH", "x"), KeyValueRowUi()))!!

        assertTrue(config.form(draft).let { it.blockingProblems == 0 })
        assertEquals(buildJsonObject { put("AUTH", "x") }, schema.saveOf(draft).values.read(headers))
    }

    @Test
    fun `a plain credential inside an entry is flagged at its field`() {
        val form = config(
            buildJsonObject {
                putJsonObject("servers") {
                    putJsonObject("github") {
                        put("command", "npx")
                        putJsonObject("env") { put("API_KEY", "sk-1") }
                    }
                }
            },
        ).form(null)

        val servers = form.field("servers")
        assertTrue(servers.problems.isEmpty())
        val env = (servers.editor as FieldEditorUi.Entries).items.single().field("env")
        assertEquals(
            listOf(
                ConfigProblemUi(ConfigProblemReasonUi.SecretLiteral, persistentListOf("API_KEY"), isBlocking = false),
            ),
            env.problems,
        )
    }

    private fun config(document: JsonObject, saved: Map<String, Long> = emptyMap()) = EngineConfigState(
        EngineId("pi"),
        schema,
        EngineConfigValues(mapOf(mcp to ConfigDocument.Json(document)), saved),
        ConfigRevision(1),
    )

    private fun pairs(vararg rows: KeyValueRowUi) = FieldInputUi.KeyValues(persistentListOf(*rows))

    private fun ConfigFormUi.field(id: String): ConfigFieldUi =
        pages.flatMap { page -> page.sections.flatMap { it.fields } }.single { it.id == id }

    private fun EntryUi.field(id: String): ConfigFieldUi = fields.single { it.id == id }

    private fun JsonObject.nested(vararg keys: String) =
        keys.dropLast(1).fold(this) { node, key -> node[key] as JsonObject }[keys.last()]

    private companion object {
        val serverKind = ConfigFieldKind.Entries(
            EntriesShape.NamedMap,
            text("Server"),
            listOf(
                EntryVariant(
                    "stdio",
                    text("Command"),
                    EntryMatch.HasKey("command"),
                    listOf(
                        EntryField(
                            "command",
                            text("Command"),
                            listOf("command"),
                            ConfigFieldKind.Text(TextRole.Command),
                        ),
                        EntryField(
                            "env",
                            text("Env"),
                            listOf("env"),
                            ConfigFieldKind.KeyValues(isSecretAllowed = true),
                        ),
                    ),
                ),
                EntryVariant(
                    "http",
                    text("HTTP"),
                    EntryMatch.HasKey("url"),
                    listOf(EntryField("url", text("URL"), listOf("url"), ConfigFieldKind.Text())),
                ),
            ),
            keyPattern = "[A-Za-z0-9_-]*",
        )
        val packageKind = ConfigFieldKind.Entries(
            EntriesShape.List,
            text("Package"),
            listOf(
                EntryVariant(
                    "source",
                    text("Whole"),
                    EntryMatch.Primitive,
                    listOf(EntryField("source", text("Source"), emptyList(), ConfigFieldKind.Text())),
                ),
                EntryVariant(
                    "filtered",
                    text("Filtered"),
                    EntryMatch.HasKey("source"),
                    listOf(EntryField("source", text("Source"), listOf("source"), ConfigFieldKind.Text())),
                ),
            ),
        )

        fun text(value: String) = LocalizedText(value)
    }
}
