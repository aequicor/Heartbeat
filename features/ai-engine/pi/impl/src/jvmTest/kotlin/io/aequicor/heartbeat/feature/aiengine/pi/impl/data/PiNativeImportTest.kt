package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportNoteReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiNativeImportTest {
    private val root = Files.createTempDirectory("pi-import")
    private val source = root.resolve("agent").createDirectories()
    private val home = root.resolve("home")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `the preview lists what exists with its notes`() {
        source.resolve("settings.json").writeText("""{"defaultModel": "gpt", "retry": {"maxRetries": 2}}""")
        source.resolve(
            "mcp.json",
        ).writeText("""{"mcpServers": {"gh": {"command": "gh", "env": {"GITHUB_TOKEN": "ghp_x"}}}}""")
        source.resolve("AGENTS.md").writeText("Be brief")
        source.resolve("auth.json").writeText("""{"anthropic": {"key": "sk"}}""")

        val preview = PiNativeImporter(source, home).preview()

        assertEquals(listOf("settings", "mcp", "agents"), preview.items.map { it.id })
        assertEquals(listOf(ImportNoteReason.OwnedKeyDropped), preview.items[0].notes.map { it.reason })
        assertEquals(listOf(ImportNoteReason.SecretMovedToVault), preview.items[1].notes.map { it.reason })
        assertFalse(preview.toString().contains(source.toString()))
    }

    @Test
    fun `settings lose owned keys and credentials move into the vault`() {
        source.resolve("settings.json").writeText("""{"defaultModel": "gpt", "transport": "sse"}""")
        source.resolve("mcp.json").writeText(
            """
            {"mcpServers": {
              "gh": {"command": "gh", "env": {"GITHUB_TOKEN": "ghp_x", "MODE": "fast", "HOME_TOKEN": "${'$'}{HOME_TOKEN}"}},
              "docs": {"url": "https://d", "headers": {"Authorization": "Bearer abc", "X-Key": "!pass show x"}}
            }}
            """.trimIndent(),
        )

        val imported = PiNativeImporter(source, home).read(ImportSelection(setOf("settings", "mcp")))

        val settings = (imported.values.documents.getValue(PiDocuments.Settings) as ConfigDocument.Json).value
        assertEquals(setOf("transport"), settings.keys)
        val servers = (imported.values.documents.getValue(PiDocuments.Mcp) as ConfigDocument.Json).value
            .getValue("mcpServers").jsonObject
        val env = servers.getValue("gh").jsonObject.getValue("env").jsonObject
        assertEquals(JsonPrimitive(secretReference("MCP_GH_GITHUB_TOKEN")), env["GITHUB_TOKEN"])
        assertEquals(JsonPrimitive("fast"), env["MODE"])
        val headers = servers.getValue("docs").jsonObject.getValue("headers").jsonObject
        assertEquals(JsonPrimitive(secretReference("MCP_DOCS_AUTHORIZATION")), headers["Authorization"])
        assertEquals(JsonPrimitive("!pass show x"), headers["X-Key"])
        assertEquals(setOf("MCP_GH_GITHUB_TOKEN", "MCP_DOCS_AUTHORIZATION"), imported.secrets.keys)
        assertEquals("ghp_x", imported.secrets.getValue("MCP_GH_GITHUB_TOKEN").reveal { String(it) })
        assertTrue(imported.notes.any { it.reason == ImportNoteReason.HostVariableNotPassed })
        imported.secrets.values.forEach { it.close() }
    }

    @Test
    fun `resources are copied without links and dependencies and listed in the settings`() {
        source.resolve("extensions/tool").createDirectories().resolve("index.ts").writeText("export default {}")
        source.resolve("extensions/tool/node_modules/dep").createDirectories().resolve("x.js").writeText("x")
        source.resolve("extensions/single.ts").writeText("export default {}")
        source.resolve("skills/review").createDirectories().resolve("SKILL.md").writeText("---\nname: review\n---")
        Files.createSymbolicLink(source.resolve("extensions/linked.ts"), source.resolve("extensions/single.ts"))

        val imported = PiNativeImporter(source, home).read(ImportSelection(setOf("resources")))

        assertEquals("export default {}", home.resolve("extensions/tool/index.ts").readText())
        assertFalse(Files.exists(home.resolve("extensions/tool/node_modules")))
        assertFalse(Files.exists(home.resolve("extensions/linked.ts")))
        assertTrue(Files.isRegularFile(home.resolve("skills/review/SKILL.md")))
        val settings = (imported.values.documents.getValue(PiDocuments.Settings) as ConfigDocument.Json).value
        assertEquals(
            setOf("extensions/single.ts", "extensions/tool"),
            (settings.getValue("extensions") as JsonArray).map { (it as JsonPrimitive).content }.toSet(),
        )
        assertEquals(JsonArray(listOf(JsonPrimitive("skills"))), settings["skills"])
        assertEquals(listOf("resources"), imported.imported)
    }

    @Test
    fun `instructions import as text documents`() {
        source.resolve("SYSTEM.md").writeText("You are Pi")

        val imported = PiNativeImporter(source, home).read(ImportSelection(setOf("system", "agents")))

        assertEquals(mapOf(PiDocuments.System to ConfigDocument.Text("You are Pi")), imported.values.documents)
        assertEquals(listOf("system"), imported.imported)
    }

    @Test
    fun `vault slots stay within the limit and distinct`() {
        val long = slotFor("a-very-long-server-name-for-testing", "ANOTHER_VERY_LONG_VARIABLE_NAME")
        assertTrue(long.length <= 48)
        assertTrue(Regex("[A-Z0-9_]+").matches(long))
        assertFalse(long == slotFor("a-very-long-server-name-for-testing", "ANOTHER_VERY_LONG_VARIABLE_NAMF"))
        assertEquals("MCP_GH_TOKEN", slotFor("gh", "token"))
    }

    @Test
    fun `a missing user configuration is reported`() = runTest {
        val configurator = DesktopPiConfigurator({ home }, { root.resolve("absent") })

        val error = assertFailsWith<ConfigException> { configurator.previewNative() }

        assertEquals(ConfigFailure.NativeMissing, error.failure)
    }

    @Test
    fun `the user configuration lives in the agent directory variable or under the home`() {
        assertEquals(Path.of("/custom/agent"), piUserAgentDirectory(mapOf("PI_CODING_AGENT_DIR" to "/custom/agent")))
        assertEquals(
            Path.of(System.getProperty("user.home"), ".pi", "agent"),
            piUserAgentDirectory(emptyMap()),
        )
    }
}
