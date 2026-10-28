package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CodexIsolationTest {
    @Test
    fun `protected and virtual sessions override an enabled MCP setting`() {
        val servers = json("pen" to json("enabled" to JsonPrimitive(true)))
        for (off in listOf(
            CodexNativeOff(isToolFree = true),
            CodexNativeOff(isActionAnalysisRequired = true),
            CodexNativeOff(isVirtualWorkspace = true),
        )) {
            val config = codexMcpOverrides(
                servers,
                CodexIsolationSettings(false, false, off = off, isMcpEnabled = true),
            )
            assertEquals(JsonPrimitive(false), config.obj("pen")["enabled"])
        }
    }

    @Test
    fun `MCP opt-in follows engine server enablement instead of enabling every server`() = runTest {
        val fixture = Fixture(this)
        fixture.nativeConfig = JsonObject(
            fixture.nativeConfig + (
                "mcp_servers" to json(
                    "pen" to json("command" to JsonPrimitive("pen")),
                    "disabled" to json("enabled" to JsonPrimitive(false)),
                )
            ),
        )
        val enabled = codexIsolationConfig(fixture.rpc, null, CodexIsolationSettings(false, false, isMcpEnabled = true))
        assertEquals(JsonPrimitive(true), enabled.obj("mcp_servers").obj("pen")["enabled"])
        assertEquals(JsonPrimitive(false), enabled.obj("mcp_servers").obj("disabled")["enabled"])
        val disabled = codexIsolationConfig(fixture.rpc, null, CodexIsolationSettings(false, false))
        assertEquals(JsonPrimitive(false), disabled.obj("mcp_servers").obj("pen")["enabled"])
        fixture.runtime.close()
    }

    @Test
    fun `each inherited native MCP is disabled rather than merging an empty table`() = runTest {
        val fixture = Fixture(this)
        fixture.nativeConfig = JsonObject(
            fixture.nativeConfig + (
                "mcp_servers" to json(
                    "foreign.with.dots" to json("enabled" to JsonPrimitive(true)),
                    "other" to json("enabled" to JsonPrimitive(true)),
                )
            ),
        )
        val session = fixture.open()
        val overrides = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params").obj("config")
        assertEquals(setOf("foreign.with.dots", "other"), overrides.obj("mcp_servers").keys)
        overrides.obj("mcp_servers").values.forEach { server ->
            assertEquals(JsonPrimitive(false), (server as JsonObject)["enabled"])
        }
        session.feature(SendsPrompts).send(Prompt)
        val turn = fixture.wire.written.single { it.text("method") == "turn/start" }.obj("params")
        assertEquals("never", turn.text("approvalPolicy"))
        assertEquals("readOnly", turn.obj("sandboxPolicy").text("type"))
        assertEquals(JsonPrimitive(false), turn.obj("sandboxPolicy")["networkAccess"])
        fixture.runtime.close()
    }

    @Test
    fun `delegation only toggles the agent feature and retains isolation`() = runTest {
        val fixture = Fixture(this)
        val off = codexIsolationConfig(fixture.rpc, null, CodexIsolationSettings(false, true))
        val on = codexIsolationConfig(fixture.rpc, null, CodexIsolationSettings(false, false, true))
        assertEquals(JsonPrimitive(false), off.obj("features")["multi_agent"])
        assertEquals(JsonPrimitive(true), on.obj("features")["multi_agent"])
        assertEquals(JsonPrimitive(true), off.obj("features")["default_mode_request_user_input"])
        assertEquals(JsonPrimitive(false), on.obj("features")["default_mode_request_user_input"])
        CodexDisabledCapabilities.forEach { assertEquals(JsonPrimitive(false), on.obj("features")[it]) }
        fixture.runtime.close()
    }

    @Test
    fun `managed native integrations cannot silently bypass the hosted trust gate`() = runTest {
        val fixture = Fixture(this)
        fixture.nativeConfig = json(
            "features" to JsonObject(
                CodexDisabledCapabilities.associateWith {
                    JsonPrimitive(it == "apps")
                },
            ),
        )
        val error = assertFailsWith<EngineException> { fixture.open() }
        assertEquals("engine.${EngineFailureReason.RequirementsNotMet}", error.failure.code)
        assertFalse(fixture.wire.written.any { it.text("method") == "thread/start" })
        fixture.runtime.close()
    }
}
