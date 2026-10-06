package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class CodexNativeOffTest {
    private val both = CodexNativeOff(isShellDisabled = true, isSearchDisabled = true)
    private val config = json(
        "features" to json("shell_tool" to JsonPrimitive(false), "apps" to JsonPrimitive(false)),
        "web_search" to "disabled".json(),
    )

    @Test
    fun `restrictions reject unknown old malformed and prerelease protocols`() {
        listOf(null, "", "0.159.99", "0.160.0-alpha.1", "codex-cli 0.160.0", "1.0", "1.0.999999999999").forEach {
            assertFailsWith<EngineException>(it) { both.requireVersion(it) }
        }
        listOf("0.160.0", "0.160.1", "0.161.0", "1.0.0").forEach(both::requireVersion)
        CodexNativeOff().requireVersion(null)
    }

    @Test
    fun `off overrides native defaults without changing hosted declarations or other isolation flags`() {
        val base = json(
            "features" to json("apps" to JsonPrimitive(false), "multi_agent" to JsonPrimitive(true)),
            "web_search" to "live".json(),
            "mcp_servers" to json("example" to json("enabled" to JsonPrimitive(false))),
        )
        val restricted = both.applyTo(base)
        assertEquals(JsonPrimitive(false), restricted.obj("features")["shell_tool"])
        assertEquals(JsonPrimitive(false), restricted.obj("features")["apps"])
        assertEquals(JsonPrimitive(true), restricted.obj("features")["multi_agent"])
        assertEquals("disabled", restricted.text("web_search"))
        assertEquals(base["mcp_servers"], restricted["mcp_servers"])
        assertEquals("live", base.text("web_search"))
        assertFalse("shell_tool" in base.obj("features"))
        assertEquals(base, CodexNativeOff().applyTo(base))
    }

    @Test
    fun `effective values and active session flags must both confirm off`() {
        both.validateConfig(response(config, config))
        both.validateConfig(
            json(
                "config" to config,
                "layers" to JsonArray(
                    listOf(json("name" to json("type" to "sessionFlags".json()), "config" to config)),
                ),
            ),
        )
        val enabled = json("features" to json("shell_tool" to JsonPrimitive(true)), "web_search" to "live".json())
        assertFailsWith<EngineException> { both.validateConfig(response(enabled, config)) }
        assertFailsWith<EngineException> { both.validateConfig(response(config, enabled)) }
        assertFailsWith<EngineException> { both.validateConfig(response(config, config, reason = "Managed".json())) }
        assertFailsWith<EngineException> { both.validateConfig(response(config, config, source = "user")) }
        assertFailsWith<EngineException> { both.validateConfig(json("config" to config)) }
        assertFailsWith<EngineException> { both.validateConfig(json("config" to config, "layers" to JsonNull)) }
    }

    @Test
    fun `a restriction validates only its own keys and rejects string booleans`() {
        val shell = json("features" to json("shell_tool" to JsonPrimitive(false)))
        val search = json("web_search" to "disabled".json())
        CodexNativeOff(isShellDisabled = true).validateConfig(response(shell, shell))
        CodexNativeOff(isSearchDisabled = true).validateConfig(response(search, search))
        val malformed = json("features" to json("shell_tool" to "false".json()))
        assertFailsWith<EngineException> {
            CodexNativeOff(isShellDisabled = true).validateConfig(response(malformed, malformed))
        }
        CodexNativeOff().validateConfig(json())
    }

    @Test
    fun `a transport without policy support refuses restrictions without opening a process`() = runTest {
        val wire = FakeWire()
        var opens = 0
        val launch = object : PreparedCodexLaunch {
            override suspend fun open(): CodexWire = wire.also { opens++ }
        }
        assertFailsWith<EngineException> { launch.open(both) }
        assertEquals(0, opens)
        assertSame(wire, launch.open(CodexNativeOff()))
        assertEquals(1, opens)
        wire.close()
    }

    private fun response(
        effective: JsonObject,
        flags: JsonObject,
        reason: kotlinx.serialization.json.JsonElement = JsonNull,
        source: String = "sessionFlags",
    ): JsonObject = json(
        "config" to effective,
        "layers" to JsonArray(
            listOf(json("name" to json("type" to source.json()), "config" to flags, "disabledReason" to reason)),
        ),
    )
}
