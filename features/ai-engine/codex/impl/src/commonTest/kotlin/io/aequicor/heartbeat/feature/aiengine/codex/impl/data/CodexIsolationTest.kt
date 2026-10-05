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
        val off = codexIsolationConfig(fixture.rpc, null, search = false, questions = true)
        val on = codexIsolationConfig(fixture.rpc, null, search = false, questions = false, subagents = true)
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
