package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretReference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.write
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiAgentConfigTest {
    private val home = Path.of("/data/pi/home")
    private val patternHome = home.toString().replace('\\', '/')
    private val agentDir = Path.of("/tmp/pi-1")
    private val noSecrets: suspend (String) -> String? = { null }

    @Test
    fun `an empty configuration keeps the hermetic command line`() = runTest {
        val config = piAgentConfig(EngineConfigValues(), home, secret = noSecrets)
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
    fun `trusting a project enables its extensions and prompts without enabling profile built-ins`() = runTest {
        val project = Files.createTempDirectory("pi-project-trust")
        try {
            Files.createDirectories(project.resolve(".pi/prompts"))
            Files.createDirectories(project.resolve(".pi/extensions"))
            Files.writeString(project.resolve(".pi/prompts/review.md"), "Review the changes")
            Files.writeString(project.resolve(".pi/extensions/custom.ts"), "export default () => {}")
            val content = piProjectContent(project)
            val values = EngineConfigValues()
            val untrusted = piAgentConfig(values, home, secret = noSecrets)
            val before = piCommand(
                Path.of("pi"),
                Path.of("s"),
                emptyList(),
                "read",
                isProject = true,
                flags = untrusted.flags(agentDir, content),
            )
            assertTrue("--no-approve" in before && "--no-extensions" in before)
            assertTrue("--prompt-template" in before)

            val trusted = piAgentConfig(values, home, isProjectTrusted = true, secret = noSecrets)
            val after = piCommand(
                Path.of("pi"),
                Path.of("s"),
                emptyList(),
                "read",
                isProject = true,
                flags = trusted.flags(agentDir),
            )
            assertTrue("--approve" in after)
            assertFalse("--no-extensions" in after || "--no-prompt-templates" in after)
            assertEquals(
                listOf("-builtin:mcp", "-builtin:codemode", "-builtin:tool-search", "-builtin:llama.cpp")
                    .map(::JsonPrimitive),
                (json(trusted.files.getValue(SETTINGS_FILE)).getValue("extensions") as JsonArray).toList(),
            )
            assertTrue(trusted.builtins.isEmpty() && trusted.tools.isEmpty())
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `trusted project discovery respects the selected profile built-ins`() = runTest {
        val values = EngineConfigValues().write(PiHostKeys.Mcp, JsonPrimitive(true))
        val config = piAgentConfig(values, home, isProjectTrusted = true, secret = noSecrets)

        assertEquals(listOf("mcp__*"), config.tools)
        assertEquals(
            listOf("-builtin:codemode", "-builtin:tool-search", "-builtin:llama.cpp").map(::JsonPrimitive),
            (json(config.files.getValue(SETTINGS_FILE)).getValue("extensions") as JsonArray).toList(),
        )
    }

    @Test
    fun `settings drop keys Heartbeat owns and resolve resource paths in the profile home`() = runTest {
        val values = EngineConfigValues().withSettings(
            """
            {
              "defaultModel": "gpt", "defaultTools": ["read"], "sessionDir": "/elsewhere",
              "retry": {"maxRetries": 5},
              "skills": ["skills", "+local/one", "+local/*.ts", "!old/**", "/abs/skill", "~/mine", "!/abs/old"],
              "prompts": ["prompts/*.md", "-prompts/draft.md"],
              "packages": ["npm:@x/y", "./pkg", {"source": "git:github.com/a/b", "skills": []}]
            }
            """,
        )

        val config = piAgentConfig(values, home, secret = noSecrets)

        val written = json(config.files.getValue(SETTINGS_FILE))
        assertFalse("defaultModel" in written || "defaultTools" in written || "sessionDir" in written)
        assertEquals(json("""{"maxRetries": 5}"""), written["retry"])
        // A relative pattern keeps its file name match and is anchored at the profile home for the relative one.
        assertEquals(
            listOf(
                "${home.resolve("skills")}",
                "+${home.resolve("local/one")}",
                "+$patternHome/local/*.ts",
                "!old/**",
                "!$patternHome/old/**",
                "/abs/skill",
                "~/mine",
                "!/abs/old",
            ).map(::JsonPrimitive),
            (written.getValue("skills") as JsonArray).toList(),
        )
        assertEquals(
            listOf(
                "prompts/*.md",
                "$patternHome/prompts/*.md",
                "-${home.resolve("prompts/draft.md")}",
            ).map(::JsonPrimitive),
            (written.getValue("prompts") as JsonArray).toList(),
        )
        // Remote packages load only once installed into the profile home.
        assertEquals(
            listOf(JsonPrimitive("${home.resolve("pkg")}")),
            (written.getValue("packages") as JsonArray).toList(),
        )
        assertTrue(config.isSkillsLoaded && config.isPromptsLoaded && config.isDiscovering)
    }

    @Test
    fun `built-in extensions load explicitly until user extensions need discovery`() = runTest {
        val explicit = piAgentConfig(
            EngineConfigValues().write(PiHostKeys.Mcp, JsonPrimitive(true)),
            home,
            secret = noSecrets,
        )
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
            secret = noSecrets,
        )
        assertEquals(emptyList(), discovering.builtins)
        assertEquals(listOf("codemode"), discovering.tools)
        val extensions = json(discovering.files.getValue(SETTINGS_FILE)).getValue("extensions")
        assertEquals(
            listOf(
                "${home.resolve("ext")}",
                "-builtin:mcp",
                "-builtin:tool-search",
                "-builtin:llama.cpp",
            ).map(::JsonPrimitive),
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

        val config = piAgentConfig(values, home, secret = noSecrets)

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
        val flags = piAgentConfig(values, home, secret = noSecrets).flags(agentDir)

        val project = piCommand(Path.of("pi"), Path.of("s"), emptyList(), "", isProject = true, flags = flags)
        val detached = piCommand(Path.of("pi"), Path.of("s"), emptyList(), "", isProject = false, flags = flags)

        assertFalse("--append-system-prompt" in project || "--no-context-files" in project)
        assertTrue("--append-system-prompt" in detached && "--no-context-files" in detached)
    }

    @Test
    fun `an untrusted project loads only its content`() {
        val project = Files.createTempDirectory("pi-project")
        try {
            Files.createDirectories(project.resolve(".pi/skills"))
            Files.createDirectories(project.resolve(".agents/skills"))
            Files.writeString(project.resolve(".pi/SYSTEM.md"), "System")
            Files.writeString(project.resolve(".pi/settings.json"), "{}")

            val content = piProjectContent(project)
            val command = piCommand(
                Path.of("pi"),
                Path.of("s"),
                emptyList(),
                "",
                true,
                flags = PiFlags(projectContent = content),
            )

            assertEquals(
                listOf(
                    "--skill",
                    project.resolve(".pi/skills").toString(),
                    "--skill",
                    project.resolve(".agents/skills").toString(),
                    "--system-prompt",
                    project.resolve(".pi/SYSTEM.md").toString(),
                ),
                content,
            )
            assertTrue("--no-approve" in command && "--approve" !in command)
            assertTrue(command.containsAll(content))
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the launch context carries configuration and trust to the process`() {
        val trust = mapOf(WorkspaceRef("p") to ProjectTrust.Trusted)
        val values = EngineConfigValues().write(PiHostKeys.Offline, JsonPrimitive(true))
        val context = LaunchContext(config = values, trust = trust, isConfigured = true)

        val startup = resolvePiStartup(context, Path.of("/bundled/pi"), "1.0.0")

        assertEquals(values, startup.config)
        assertEquals(trust, startup.trust)
        assertTrue(startup.isConfigured)
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
