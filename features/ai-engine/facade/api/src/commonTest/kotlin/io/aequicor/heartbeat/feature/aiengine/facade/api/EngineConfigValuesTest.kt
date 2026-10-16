package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineConfigValuesTest {
    private val schema = TestConfig.schema

    @Test
    fun `writing a path creates parents and reading returns the value`() {
        val values = EngineConfigValues().write(TestConfig.retries, JsonPrimitive(5))
        assertEquals(JsonPrimitive(5), values.read(TestConfig.retries))
        assertEquals(json("""{"retry":{"maxRetries":5}}"""), settingsOf(values))
        assertNull(values.read(TestConfig.retryEnabled))
    }

    @Test
    fun `removing the last value prunes empty parents and the document`() {
        val values = EngineConfigValues()
            .write(TestConfig.retries, JsonPrimitive(5))
            .write(TestConfig.transport, JsonPrimitive("sse"))
        val withoutRetry = values.write(TestConfig.retries, null)
        assertEquals(json("""{"transport":"sse"}"""), settingsOf(withoutRetry))
        assertTrue(withoutRetry.write(TestConfig.transport, JsonNull).isEmpty)
    }

    @Test
    fun `unknown keys survive writes`() {
        val raw = json("""{"retry":{"maxRetries":1,"future":true},"custom":{"a":1}}""")
        val values = EngineConfigValues(mapOf(TestConfig.settings to ConfigDocument.Json(raw)))
            .write(TestConfig.retries, JsonPrimitive(2))
        assertEquals(
            json("""{"retry":{"maxRetries":2,"future":true},"custom":{"a":1}}"""),
            settingsOf(values),
        )
    }

    @Test
    fun `a whole document reads and writes text`() {
        val values = EngineConfigValues().write(TestConfig.instructions, JsonPrimitive("Be brief"))
        assertEquals(JsonPrimitive("Be brief"), values.read(TestConfig.instructions))
        assertTrue(values.write(TestConfig.instructions, JsonPrimitive("")).isEmpty)
        assertFailsWith<IllegalArgumentException> { values.write(TestConfig.instructions, JsonPrimitive(1)) }
    }

    @Test
    fun `a path through a scalar is absent and is replaced on write`() {
        val raw = json("""{"retry":true}""")
        val values = EngineConfigValues(mapOf(TestConfig.settings to ConfigDocument.Json(raw)))
        assertNull(values.read(TestConfig.retries))
        assertEquals(JsonPrimitive(1), values.write(TestConfig.retries, JsonPrimitive(1)).read(TestConfig.retries))
    }

    @Test
    fun `secret references round trip and are collected`() {
        val reference = secretReference("MCP_GITHUB_1")
        assertEquals("\${HEARTBEAT_SECRET_MCP_GITHUB_1}", reference)
        assertEquals("MCP_GITHUB_1", secretSlotOf(reference))
        assertNull(secretSlotOf("prefix $reference"))
        assertFailsWith<IllegalArgumentException> { secretReference("lower") }
        val values = servers("""{"gh":{"command":"gh","env":{"GITHUB_TOKEN":"$reference"}}}""")
        assertEquals(setOf("MCP_GITHUB_1"), values.secretSlots())
    }

    @Test
    fun `values never print their content`() {
        val values = EngineConfigValues().write(TestConfig.proxy, JsonPrimitive("http://user:pass@proxy"))
        assertFalse("proxy" in values.toString())
        assertFalse("pass" in requireNotNull(values.document(TestConfig.settings)).toString())
    }

    @Test
    fun `valid values have no problems`() {
        val values = EngineConfigValues()
            .write(TestConfig.retries, JsonPrimitive(3))
            .write(TestConfig.transport, JsonPrimitive("sse"))
            .write(TestConfig.proxy, JsonPrimitive("http://proxy:8080"))
            .write(TestConfig.skills, buildJsonArray { add(JsonPrimitive("!old/**")) })
            .write(TestConfig.instructions, JsonPrimitive("Be brief"))
        assertEquals(emptyList(), validateEngineConfig(schema, values, ConfigScope.Profile))
    }

    @Test
    fun `field values are checked against their kind`() {
        val values = EngineConfigValues()
            .write(TestConfig.retries, JsonPrimitive(11))
            .write(TestConfig.retryEnabled, JsonPrimitive("yes"))
            .write(TestConfig.transport, JsonPrimitive("carrier-pigeon"))
            .write(TestConfig.proxy, JsonPrimitive("http://a\nb"))
            .write(TestConfig.skills, buildJsonArray { repeat(4) { add(JsonPrimitive("x")) } })
        assertEquals(
            setOf(
                "retry_max" to ConfigProblemReason.OutOfRange,
                "retry_enabled" to ConfigProblemReason.WrongType,
                "transport" to ConfigProblemReason.NotAnOption,
                "proxy" to ConfigProblemReason.ControlCharacter,
                "skills" to ConfigProblemReason.TooLong,
            ),
            validateEngineConfig(schema, values, ConfigScope.Profile).map { it.field to it.reason }.toSet(),
        )
    }

    @Test
    fun `numbers given as strings are rejected`() {
        val values = EngineConfigValues().write(TestConfig.retries, JsonPrimitive("3"))
        assertEquals(
            listOf(ConfigProblemReason.WrongType),
            validateEngineConfig(schema, values, ConfigScope.Profile).map { it.reason },
        )
    }

    @Test
    fun `profile only documents and fields are refused in a project`() {
        val values = EngineConfigValues()
            .write(TestConfig.proxy, JsonPrimitive("http://proxy"))
            .write(TestConfig.offline, JsonPrimitive(true))
        val problems = validateEngineConfig(schema, values, ConfigScope.Project)
        assertTrue(problems.all { it.reason == ConfigProblemReason.NotInScope && it.isBlocking })
        assertEquals(setOf(TestConfig.settings, TestConfig.host), problems.map { it.document }.toSet())
    }

    @Test
    fun `documents are checked for format and size`() {
        val values = EngineConfigValues(
            mapOf(
                TestConfig.agents to ConfigDocument.Text("x".repeat(65)),
                TestConfig.mcp to ConfigDocument.Text("{}"),
                ConfigDocumentId("unknown") to ConfigDocument.Text("x"),
            ),
        )
        assertEquals(
            setOf(ConfigProblemReason.TooLong, ConfigProblemReason.WrongFormat, ConfigProblemReason.UnknownDocument),
            validateEngineConfig(schema, values, ConfigScope.Profile).map { it.reason }.toSet(),
        )
    }

    @Test
    fun `entries are matched to variants and their fields checked`() {
        val values = servers(
            """
            {
              "local": {"command": "srv", "timeout": 0},
              "remote": {"url": "https://mcp.example"},
              "bad name": {"command": "x"},
              "odd": {"cwd": "/tmp"}
            }
            """,
        )
        assertEquals(
            setOf(
                listOf("mcpServers", "local", "timeout") to ConfigProblemReason.OutOfRange,
                listOf("mcpServers", "bad name") to ConfigProblemReason.InvalidKey,
                listOf("mcpServers", "odd") to ConfigProblemReason.UnknownVariant,
            ),
            validateEngineConfig(schema, values, ConfigScope.Profile).map { it.path to it.reason }.toSet(),
        )
    }

    @Test
    fun `owned keys are reported without blocking`() {
        val raw = json("""{"defaultModel":"gpt"}""")
        val problems = validateEngineConfig(
            schema,
            EngineConfigValues(mapOf(TestConfig.settings to ConfigDocument.Json(raw))),
            ConfigScope.Profile,
        )
        assertEquals(
            listOf(ConfigProblem(TestConfig.settings, listOf("defaultModel"), ConfigProblemReason.Owned, false)),
            problems,
        )
    }

    @Test
    fun `secret references are profile only and must exist`() {
        val reference = secretReference("GH")
        val values = servers("""{"gh":{"command":"gh","env":{"GITHUB_TOKEN":"$reference"}}}""")
        val missing = validateEngineConfig(schema, values, ConfigScope.Profile).single()
        assertEquals(ConfigProblemReason.MissingSecret, missing.reason)
        assertFalse(missing.isBlocking)
        assertEquals(
            emptyList(),
            validateEngineConfig(schema, values.copy(secrets = mapOf("GH" to 1L)), ConfigScope.Profile),
        )
        val project = validateEngineConfig(schema, values, ConfigScope.Project).single()
        assertEquals(ConfigProblemReason.SecretInProject, project.reason)
        assertTrue(project.isBlocking)
    }

    @Test
    fun `plain credentials are flagged but host references are not`() {
        val values = servers(
            """
            {
              "a": {"url": "https://a", "headers": {"Authorization": "Bearer abc"}},
              "b": {"command": "b", "env": {"API_TOKEN": "${'$'}{API_TOKEN}", "MODE": "fast"}},
              "c": {"command": "c", "env": {"OTHER_KEY": "!pass show key"}}
            }
            """,
        )
        val problems = validateEngineConfig(schema, values, ConfigScope.Profile)
        assertEquals(
            listOf(listOf("mcpServers", "a", "headers", "Authorization") to ConfigProblemReason.SecretLiteral),
            problems.map { it.path to it.reason },
        )
        assertFalse(problems.single().isBlocking)
    }

    @Test
    fun `entry matches recognize item forms`() {
        val item = json("""{"type":"http","url":"u"}""")
        assertTrue(EntryMatch.HasKey("url").accepts(item))
        assertTrue(EntryMatch.KeyEquals("type", JsonPrimitive("http")).accepts(item))
        assertFalse(EntryMatch.KeyEquals("type", JsonPrimitive("stdio")).accepts(item))
        assertTrue(EntryMatch.AnyObject.accepts(item))
        assertFalse(EntryMatch.Primitive.accepts(item))
        assertTrue(EntryMatch.Primitive.accepts(JsonPrimitive("npm:pkg")))
    }

    private fun servers(text: String): EngineConfigValues = EngineConfigValues().write(TestConfig.servers, json(text))

    private fun settingsOf(values: EngineConfigValues): JsonObject =
        (requireNotNull(values.document(TestConfig.settings)) as ConfigDocument.Json).value

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text.trimIndent()).jsonObject
}
