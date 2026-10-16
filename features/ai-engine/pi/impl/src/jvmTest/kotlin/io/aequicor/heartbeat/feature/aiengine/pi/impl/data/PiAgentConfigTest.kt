package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretReference
import io.aequicor.heartbeat.feature.aiengine.facade.api.write
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiAgentConfigTest {
    private val home = Path.of("/data/pi/home")
    private val agentDir = Path.of("/tmp/pi-1")
    private val noSecrets: suspend (String) -> String? = { null }

    @Test
    fun `an empty configuration keeps the hermetic command line`() = runTest {
        val config = piAgentConfig(EngineConfigValues(), home, noSecrets)
        assertEquals(PiAgentConfig(), config)
        val extensions = piExtensions(agentDir, searchTools = false)
        listOf(true, false).forEach { isProject ->
            assertEquals(
                legacyCommand(isProject, extensions),
                piCommand(Path.of("pi"), Path.of("s"), extensions, "read", isProject, "id", config.flags(agentDir)),
            )
        }
    }

    @Test
    fun `settings drop keys Heartbeat owns and resolve resource paths in the profile home`() = runTest {
        val values = EngineConfigValues().withSettings(
            """
            {
              "defaultModel": "gpt", "defaultTools": ["read"], "sessionDir": "/elsewhere",
              "retry": {"maxRetries": 5},
              "skills": ["skills", "+local/one", "!old/**", "/abs/skill", "~/mine"],
              "packages": ["npm:@x/y", "./pkg", {"source": "git:github.com/a/b", "skills": []}]
            }
            """,
        )

        val config = piAgentConfig(values, home, noSecrets)

        val written = json(config.files.getValue(SETTINGS_FILE))
        assertFalse("defaultModel" in written || "defaultTools" in written || "sessionDir" in written)
        assertEquals(json("""{"maxRetries": 5}"""), written["retry"])
        assertEquals(
            listOf("$home/skills", "+$home/local/one", "!old/**", "/abs/skill", "~/mine").map(::JsonPrimitive),
            (written.getValue("skills") as JsonArray).toList(),
        )
        val packages = written.getValue("packages") as JsonArray
        assertEquals(JsonPrimitive("npm:@x/y"), packages[0])
        assertEquals(JsonPrimitive("$home/pkg"), packages[1])
        assertTrue(config.isSkillsLoaded && config.isPromptsLoaded && config.isDiscovering)
    }

    @Test
    fun `built-in extensions load explicitly until user extensions need discovery`() = runTest {
        val explicit = piAgentConfig(EngineConfigValues().write(PiHostKeys.Mcp, JsonPrimitive(true)), home, noSecrets)
        assertEquals(listOf("builtin:mcp"), explicit.builtins)
        assertEquals(listOf("mcp__*"), explicit.tools)
        val command = piCommand(Path.of("pi"), Path.of("s"), emptyList(), "read", flags = explicit.flags(agentDir))
        assertTrue("--no-extensions" in command)
        assertEquals("builtin:mcp", command[command.indexOf("-e") + 1])

        val discovering = piAgentConfig(
            EngineConfigValues().write(
                PiHostKeys.Codemode,
                JsonPrimitive(true),
            ).withSettings("""{"extensions": ["ext"]}"""),
            home,
            noSecrets,
        )
        assertEquals(emptyList(), discovering.builtins)
        assertEquals(listOf("codemode"), discovering.tools)
        val extensions = json(discovering.files.getValue(SETTINGS_FILE)).getValue("extensions")
        assertEquals(
            listOf("$home/ext", "-builtin:mcp", "-builtin:tool-search", "-builtin:llama.cpp").map(::JsonPrimitive),
            (extensions as JsonArray).toList(),
        )
        assertFalse(
            "--no-extensions" in piCommand(
                Path.of("pi"),
                Path.of("s"),
                emptyList(),
                "",
                flags = discovering.flags(agentDir),
            ),
        )
    }

    @Test
    fun `vault references are substituted and servers missing one are switched off`() = runTest {
        val values = EngineConfigValues().write(
            ConfigBinding.JsonPath(PiDocuments.Mcp, listOf("mcpServers")),
            json(
                """
                {
                  "gh": {"command": "gh", "env": {"GITHUB_TOKEN": "${secretReference("GH")}"}},
                  "docs": {"url": "https://d", "headers": {"Authorization": "${secretReference("GONE")}"}}
                }
                """,
            ),
        )

        val config = piAgentConfig(values, home) { slot -> "value-$slot".takeIf { slot == "GH" } }

        val servers = json(config.files.getValue(MCP_FILE)).getValue("mcpServers").jsonObject
        assertEquals(
            JsonPrimitive("value-GH"),
            servers.getValue("gh").jsonObject.getValue("env").jsonObject["GITHUB_TOKEN"],
        )
        assertEquals(JsonPrimitive(false), servers.getValue("docs").jsonObject["enabled"])
        assertEquals(1, config.missingSecrets)
        assertFalse("value-GH" in config.toString())
    }

    @Test
    fun `instructions environment and extension tools reach the process`() = runTest {
        val values = EngineConfigValues()
            .write(ConfigBinding.WholeDocument(PiDocuments.Agents), JsonPrimitive("Be brief"))
            .write(ConfigBinding.WholeDocument(PiDocuments.System), JsonPrimitive("You are Pi"))
            .write(PiHostKeys.Offline, JsonPrimitive(true))
            .write(PiHostKeys.LongCacheRetention, JsonPrimitive(true))
            .write(PiHostKeys.ProjectContextFiles, JsonPrimitive(false))
            .write(PiHostKeys.ExtensionTools, Json.parseToJsonElement("""["my_*"]"""))

        val config = piAgentConfig(values, home, noSecrets)

        assertEquals("Be brief", config.files[AGENTS_FILE])
        assertEquals("You are Pi", config.files[SYSTEM_FILE])
        assertEquals(mapOf("PI_OFFLINE" to "1", "PI_CACHE_RETENTION" to "long"), config.environment)
        assertEquals(listOf("my_*"), config.tools)
        val project = piCommand(
            Path.of("pi"),
            Path.of("s"),
            emptyList(),
            "",
            isProject = true,
            flags = config.flags(agentDir),
        )
        assertTrue("--no-context-files" in project)
        assertEquals(agentDir.resolve(AGENTS_FILE).toString(), project[project.indexOf("--append-system-prompt") + 1])
    }

    @Test
    fun `project sessions read the profile instructions through context discovery`() = runTest {
        val values = EngineConfigValues().write(
            ConfigBinding.WholeDocument(PiDocuments.Agents),
            JsonPrimitive("Be brief"),
        )
        val flags = piAgentConfig(values, home, noSecrets).flags(agentDir)

        val project = piCommand(Path.of("pi"), Path.of("s"), emptyList(), "", isProject = true, flags = flags)
        val detached = piCommand(Path.of("pi"), Path.of("s"), emptyList(), "", isProject = false, flags = flags)

        assertFalse("--append-system-prompt" in project || "--no-context-files" in project)
        assertTrue("--append-system-prompt" in detached && "--no-context-files" in detached)
    }

    private fun legacyCommand(isProject: Boolean, extensions: List<Path>): List<String> =
        listOf("pi", "--mode", "rpc", "--session-dir", "s", "--no-extensions") +
            extensions.flatMap { listOf("-e", it.toString()) } +
            (if (isProject) listOf("--approve") else listOf("--no-approve", "--no-skills", "--no-context-files")) +
            listOf("--session-id", "id", "--no-prompt-templates", "--no-themes", "--tools", "read")

    private fun EngineConfigValues.withSettings(text: String): EngineConfigValues =
        json(text).entries.fold(this) { values, (key, value) -> values.write(settings(key), value) }

    private fun settings(key: String) = ConfigBinding.JsonPath(PiDocuments.Settings, listOf(key))

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text.trimIndent()).jsonObject
}
