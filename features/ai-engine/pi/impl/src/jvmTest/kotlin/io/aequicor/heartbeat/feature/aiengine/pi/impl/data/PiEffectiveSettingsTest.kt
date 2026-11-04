package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.MASKED_SETTING_VALUE
import io.aequicor.heartbeat.feature.aiengine.facade.api.ObservedValue
import io.aequicor.heartbeat.feature.aiengine.facade.api.SettingsLayer
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretReference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EffectiveSettingsRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineDeclaredResolution
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineEffectiveSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.write
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiEffectiveSettingsTest {
    private val home = Files.createTempDirectory("pi-effective-home")
    private val configurator = DesktopPiConfigurator({ home })

    @AfterTest
    fun cleanUp() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `settings written into the agent directory are observed with the profile resolution`() = runTest {
        val profile = EngineConfigValues()
            .write(settings("retry", "maxRetries"), JsonPrimitive(7))
            .write(settings("transport"), JsonPrimitive("websocket"))
            .write(settings("compaction", "enabled"), JsonPrimitive(false))

        val answer = observe(EffectiveSettingsRequest(profile))

        assertEquals(ObservedValue.Literal(JsonPrimitive(7)), answer.values["retry_max"])
        assertEquals(ObservedValue.Literal(JsonPrimitive("websocket")), answer.values["transport"])
        assertEquals(ObservedValue.Literal(JsonPrimitive(false)), answer.values["compaction_enabled"])
        assertEquals(setOf("retry_max", "transport", "compaction_enabled"), answer.values.keys)
        assertResolution(answer, "retry_max", JsonPrimitive(7))
        assertResolution(answer, "transport", JsonPrimitive("websocket"))
        assertResolution(answer, "compaction_enabled", JsonPrimitive(false))
    }

    @Test
    fun `a key Heartbeat does not write is never claimed`() = runTest {
        val profile = EngineConfigValues().write(settings("transport"), JsonPrimitive("auto"))

        val answer = observe(EffectiveSettingsRequest(profile))

        // Pi resolves its own defaults for these keys; Heartbeat writes nothing at their paths.
        assertFalse("retry_max" in answer.values)
        assertFalse("images_resize" in answer.values)
        assertFalse("compaction_reserve" in answer.values)
        assertFalse("cache_warming" in answer.values)
        assertNull(answer.merged["retry_max"])
        assertEquals(setOf("transport"), answer.values.keys)
    }

    @Test
    fun `heartbeat launch choices and owned keys are not the engine layer`() = runTest {
        val profile = EngineConfigValues()
            .write(PiHostKeys.LongCacheRetention, JsonPrimitive(true))
            .write(PiHostKeys.Offline, JsonPrimitive(true))
            .write(PiHostKeys.ProjectContextFiles, JsonPrimitive(false))
            .write(PiHostKeys.Mcp, JsonPrimitive(true))
            .write(PiHostKeys.ExtensionTools, Json.parseToJsonElement("[\"my_*\"]"))
            .write(settings("defaultModel"), JsonPrimitive("gpt"))
            .write(settings("defaultTools"), Json.parseToJsonElement("[\"read\"]"))

        val answer = observe(EffectiveSettingsRequest(profile))

        val launchOnly = setOf(
            "cache_long",
            "offline",
            "context_files",
            "builtin_mcp",
            "builtin_codemode",
            "builtin_tool_search",
            "builtin_llama_cpp",
            "extension_tools",
            "model",
            "thinking",
        )
        assertTrue(answer.values.keys.none { it in launchOnly }, answer.values.keys.toString())
        assertTrue(answer.merged.isEmpty())
    }

    @Test
    fun `MCP content is not claimed because the launch decides whether Pi sees it`() = runTest {
        val profile = EngineConfigValues()
            .write(
                ConfigBinding.JsonPath(PiDocuments.Mcp, listOf("mcpServers")),
                Json.parseToJsonElement("""{"gh": {"command": "gh"}}"""),
            )
            .write(ConfigBinding.JsonPath(PiDocuments.Mcp, listOf("autoEnableCodemode")), JsonPrimitive(false))

        val answer = observe(EffectiveSettingsRequest(profile))

        assertFalse("servers" in answer.values)
        assertFalse("auto_codemode" in answer.values)
    }

    @Test
    fun `instruction documents are observed without a project merge`() = runTest {
        val profile = EngineConfigValues()
            .write(ConfigBinding.WholeDocument(PiDocuments.Agents), JsonPrimitive("Be brief"))
            .write(ConfigBinding.WholeDocument(PiDocuments.System), JsonPrimitive("You are Pi"))
            .write(ConfigBinding.WholeDocument(PiDocuments.AppendSystem), JsonPrimitive("Cite files"))
        val project = EngineConfigValues()
            .write(ConfigBinding.WholeDocument(PiDocuments.Agents), JsonPrimitive("Project rules"))

        val answer = observe(EffectiveSettingsRequest(profile, project))

        assertEquals(ObservedValue.Literal(JsonPrimitive("Be brief")), answer.values["agents"])
        assertEquals(ObservedValue.Literal(JsonPrimitive("You are Pi")), answer.values["system"])
        assertEquals(ObservedValue.Literal(JsonPrimitive("Cite files")), answer.values["append_system"])
        // Project instructions are additive or replacing in a way Heartbeat does not prove; the facade owns them.
        assertTrue(answer.merged.keys.none { it in setOf("agents", "system", "append_system") })
    }

    @Test
    fun `resource and package entries are the values Pi reads, not the profile text`() = runTest {
        val profile = EngineConfigValues()
            .write(settings("skills"), Json.parseToJsonElement("[\"/opt/skill\"]"))
            .write(settings("extensions"), Json.parseToJsonElement("[\"/opt/ext\"]"))
            .write(settings("prompts"), Json.parseToJsonElement("[\"prompts/*.md\"]"))
            .write(settings("packages"), Json.parseToJsonElement("[\"npm:@x/y\", \"/opt/pkg\"]"))

        val answer = observe(EffectiveSettingsRequest(profile))

        assertEquals(
            Json.parseToJsonElement("[\"/opt/skill\"]"),
            literal(answer, "skills"),
        )
        assertResolution(answer, "skills", Json.parseToJsonElement("[\"/opt/skill\"]"))
        assertEquals(
            Json.parseToJsonElement(
                "[\"/opt/ext\", \"-builtin:mcp\", \"-builtin:codemode\", \"-builtin:tool-search\", " +
                    "\"-builtin:llama.cpp\"]",
            ),
            literal(answer, "extensions"),
        )
        assertNull(answer.merged["extensions"])
        assertEquals(
            Json.parseToJsonElement("[\"prompts/*.md\", \"$anchoredHome/prompts/*.md\"]"),
            literal(answer, "prompts"),
        )
        assertNull(answer.merged["prompts"])
        assertEquals(Json.parseToJsonElement("[\"/opt/pkg\"]"), literal(answer, "packages"))
        assertNull(answer.merged["packages"])
    }

    @Test
    fun `vault references and secret named entries never reach the answer`() = runTest {
        val profile = EngineConfigValues()
            .write(settings("transport"), JsonPrimitive(secretReference("PROXY")))
            .write(
                settings("compaction", "modelOverrides"),
                Json.parseToJsonElement("""{"openai/gpt": {"reserveTokens": 1, "apiKey": "hunter2"}}"""),
            )

        val answer = observe(EffectiveSettingsRequest(profile))

        assertEquals(ObservedValue.Secret, answer.values["transport"])
        assertEquals(ObservedValue.Secret, answer.merged.getValue("transport").value)
        val models = literal(answer, "compaction_models").toString()
        val merged = mergedLiteral(answer, "compaction_models").toString()
        assertTrue(MASKED_SETTING_VALUE in models && MASKED_SETTING_VALUE in merged, "models=$models merged=$merged")
        assertFalse("hunter2" in models || "hunter2" in merged)
        assertFalse("HEARTBEAT_SECRET" in models || "HEARTBEAT_SECRET" in merged)
    }

    @Test
    fun `project values never become the engine layer`() = runTest {
        val profile = EngineConfigValues().write(settings("retry", "maxRetries"), JsonPrimitive(7))
        val project = EngineConfigValues()
            .write(settings("retry", "maxRetries"), JsonPrimitive(9))
            .write(ConfigBinding.WholeDocument(PiDocuments.Agents), JsonPrimitive("Project rules"))

        val answer = observe(EffectiveSettingsRequest(profile, project))

        assertEquals(ObservedValue.Literal(JsonPrimitive(7)), answer.values["retry_max"])
        assertFalse("agents" in answer.values)
        // The project may override the same key; its layer stays with the facade instead of becoming a resolution.
        assertNull(answer.merged["retry_max"])
    }

    @Test
    fun `a project without profile settings adds no engine claim`() = runTest {
        val project = EngineConfigValues()
            .write(settings("retry", "maxRetries"), JsonPrimitive(9))
            .write(ConfigBinding.WholeDocument(PiDocuments.Agents), JsonPrimitive("Project rules"))

        val answer = observe(EffectiveSettingsRequest(EngineConfigValues(), project))

        assertTrue(answer.values.isEmpty(), answer.values.keys.toString())
        assertTrue(answer.merged.isEmpty())
    }

    @Test
    fun `a changed request is observed anew without the old claim`() = runTest {
        val first = observe(
            EffectiveSettingsRequest(EngineConfigValues().write(settings("retry", "maxRetries"), JsonPrimitive(7))),
        )
        val second = observe(
            EffectiveSettingsRequest(EngineConfigValues().write(settings("transport"), JsonPrimitive("auto"))),
        )

        assertNotNull(first.merged["retry_max"])
        assertFalse("retry_max" in second.values)
        assertNull(second.merged["retry_max"])
    }

    @Test
    fun `the observation reads no native configuration and starts no process`() = runTest {
        val isolated = DesktopPiConfigurator(
            home = { home },
            userAgentDirectory = { error("native configuration must not be read") },
            commands = { _, _ -> error("no process may start") },
        )
        val profile = EngineConfigValues().write(settings("retry", "maxRetries"), JsonPrimitive(7))

        val answer = isolated.effectiveSettings(EffectiveSettingsRequest(profile))

        assertEquals(ObservedValue.Literal(JsonPrimitive(7)), requireNotNull(answer).values["retry_max"])
    }

    @Test
    fun `without a profile home the adapter claims nothing`() = runTest {
        val unavailable = DesktopPiConfigurator({ null })
        val profile = EngineConfigValues().write(settings("retry", "maxRetries"), JsonPrimitive(7))

        assertNull(unavailable.effectiveSettings(EffectiveSettingsRequest(profile)))
    }

    @Test
    fun `a cancelled observation makes no claim`() = runTest {
        val profile = EngineConfigValues().write(settings("retry", "maxRetries"), JsonPrimitive(7))
        val cancelled = Job().apply { cancel() }

        assertFailsWith<CancellationException> {
            withContext(coroutineContext + cancelled) {
                configurator.effectiveSettings(EffectiveSettingsRequest(profile))
            }
        }
    }

    private suspend fun observe(request: EffectiveSettingsRequest): EngineEffectiveSettings =
        requireNotNull(configurator.effectiveSettings(request))

    private fun literal(answer: EngineEffectiveSettings, id: String): JsonElement =
        (answer.values.getValue(id) as ObservedValue.Literal).value

    private fun mergedLiteral(answer: EngineEffectiveSettings, id: String): JsonElement =
        (answer.merged.getValue(id).value as ObservedValue.Literal).value

    private fun assertResolution(answer: EngineEffectiveSettings, id: String, value: JsonElement) {
        assertEquals(
            EngineDeclaredResolution(ObservedValue.Literal(value), VERBATIM),
            answer.merged[id],
        )
    }

    private fun settings(vararg path: String) = ConfigBinding.JsonPath(PiDocuments.Settings, path.toList())

    /** Pi compares patterns with `/`, like the composition anchors them. */
    private val anchoredHome: String get() = home.toString().replace('\\', '/').trimEnd('/')

    private companion object {
        val VERBATIM = setOf(SettingsLayer.Heartbeat, SettingsLayer.Engine)
    }
}
