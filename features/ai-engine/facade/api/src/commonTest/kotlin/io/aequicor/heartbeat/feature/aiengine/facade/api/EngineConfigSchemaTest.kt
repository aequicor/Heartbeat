package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineConfigSchemaTest {
    @Test
    fun `a consistent schema has no problems`() {
        assertEquals(emptyList(), validateEngineConfigSchema(TestConfig.schema))
    }

    @Test
    fun `localized text falls back to the default language`() {
        val text = LocalizedText("Retries", mapOf("ru" to "Повторы"))
        assertEquals("Повторы", text.resolve("ru"))
        assertEquals("Retries", text.resolve("de"))
    }

    @Test
    fun `identities and paths are validated on construction`() {
        assertFailsWith<IllegalArgumentException> { ConfigDocumentId("Settings") }
        assertFailsWith<IllegalArgumentException> { ConfigBinding.JsonPath(TestConfig.settings, emptyList()) }
        assertFailsWith<IllegalArgumentException> { ConfigBinding.JsonPath(TestConfig.settings, listOf("a", "")) }
        assertFailsWith<IllegalArgumentException> {
            ConfigDocumentSpec(
                TestConfig.host,
                LocalizedText("Host"),
                ConfigFormat.Json,
                ConfigScope.entries.toSet(),
                "host",
                isHostOnly = true,
            )
        }
    }

    @Test
    fun `duplicate identities are reported`() {
        val page = TestConfig.schema.pages.first()
        val schema = TestConfig.schema.copy(
            documents = TestConfig.schema.documents + TestConfig.schema.documents.first(),
            pages = TestConfig.schema.pages + page,
        )
        val problems = validateEngineConfigSchema(schema)
        assertTrue("Duplicate document id" in problems)
        assertTrue("Duplicate page id" in problems)
        assertTrue(problems.any { it.startsWith("Duplicate field id") })
    }

    @Test
    fun `bindings must match the document format and scope`() {
        val wrongFormat = field(
            ConfigBinding.JsonPath(TestConfig.agents, listOf("x")),
            ConfigFieldKind.Flag(),
        )
        val wholeJson = field(ConfigBinding.WholeDocument(TestConfig.settings), ConfigFieldKind.Document())
        val wideScope = field(
            ConfigBinding.JsonPath(TestConfig.host, listOf("offline")),
            ConfigFieldKind.Flag(),
        )
        val unknown = field(ConfigBinding.JsonPath(ConfigDocumentId("other"), listOf("x")), ConfigFieldKind.Flag())
        val textCondition = field(TestConfig.retries, ConfigFieldKind.Flag()).copy(
            visibleWhen = ConfigCondition(ConfigBinding.JsonPath(TestConfig.agents, listOf("x")), JsonPrimitive(true)),
        )
        listOf(wrongFormat, wholeJson, wideScope, unknown, textCondition).forEach { field ->
            assertTrue(validateEngineConfigSchema(schemaWith(field)).isNotEmpty(), field.toString())
        }
    }

    @Test
    fun `malformed kinds are reported`() {
        val path = ConfigBinding.JsonPath(TestConfig.settings, listOf("x"))
        val kinds = listOf(
            ConfigFieldKind.Choice(emptyList()),
            ConfigFieldKind.Choice(listOf(option("a")), default = JsonPrimitive("b")),
            ConfigFieldKind.Number(min = 10, max = 1),
            ConfigFieldKind.Number(min = 1, default = 0),
            ConfigFieldKind.KeyValues(keyPattern = "("),
            ConfigFieldKind.Entries(EntriesShape.List, LocalizedText("Item"), emptyList()),
            ConfigFieldKind.Entries(
                EntriesShape.NamedMap,
                LocalizedText("Item"),
                listOf(EntryVariant("p", LocalizedText("P"), EntryMatch.Primitive, listOf(entryField(emptyList())))),
            ),
            ConfigFieldKind.Entries(
                EntriesShape.List,
                LocalizedText("Item"),
                listOf(EntryVariant("o", LocalizedText("O"), EntryMatch.AnyObject, listOf(entryField(emptyList())))),
            ),
            ConfigFieldKind.Entries(
                EntriesShape.List,
                LocalizedText("Item"),
                listOf(
                    EntryVariant(
                        "o",
                        LocalizedText("O"),
                        EntryMatch.AnyObject,
                        listOf(entryField(listOf("nested"), ConfigFieldKind.Document())),
                    ),
                ),
            ),
        )
        kinds.forEach { kind ->
            assertTrue(validateEngineConfigSchema(schemaWith(field(path, kind))).isNotEmpty(), kind.toString())
        }
    }

    @Test
    fun `owned keys must live in JSON documents`() {
        val schema = TestConfig.schema.copy(
            ownedKeys = listOf(OwnedKey(TestConfig.agents, listOf("model"), LocalizedText("Chosen in chat"))),
        )
        assertEquals(listOf("Owned key in a non-JSON document"), validateEngineConfigSchema(schema))
    }

    private fun schemaWith(field: ConfigField): EngineConfigSchema = TestConfig.schema.copy(
        pages = listOf(
            ConfigPage("extra", LocalizedText("Extra"), listOf(ConfigSection("s", LocalizedText("S"), listOf(field)))),
        ),
    )

    private fun field(binding: ConfigBinding, kind: ConfigFieldKind) =
        ConfigField("extra_field", LocalizedText("Extra"), binding, kind)

    private fun entryField(path: List<String>, kind: ConfigFieldKind = ConfigFieldKind.Text()) =
        EntryField("value", LocalizedText("Value"), path, kind)

    private fun option(value: String) = ConfigOption(JsonPrimitive(value), LocalizedText(value))
}
