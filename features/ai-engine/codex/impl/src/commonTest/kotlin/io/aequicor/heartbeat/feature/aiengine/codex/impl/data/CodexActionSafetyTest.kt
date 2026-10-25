@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexActionSafetyTest {
    @Test
    fun `protected creation checks the selected model inventory before starting a thread`() = runTest {
        val fixture = Fixture(this, tools = SafetyTools())
        var checks = 0
        fixture.wire.verifyInventory = { target, off ->
            checks++
            assertEquals("model", target.model)
            assertTrue(off.isVerifiedInventoryRequired)
            assertEquals(JsonPrimitive(false), target.effective.obj("features")["view_image"])
            assertTrue(fixture.requests("thread/start").isEmpty())
        }
        fixture.open()
        assertEquals(1, checks)
        fixture.runtime.close()
    }

    @Test
    fun `protected probe receives the working thread config and hosted declarations`() = runTest {
        val fixture = Fixture(this, tools = SafetyTools())
        val workspace = WorkspaceRef("project")
        fixture.workspacePaths[workspace] = "/project"
        var probed: CodexInventoryTarget? = null
        fixture.wire.verifyInventory = { target, _ -> probed = target }
        fixture.open(workspace)
        val start = fixture.requests("thread/start").single().obj("params")
        val target = checkNotNull(probed)
        assertEquals(start.obj("config"), target.thread)
        assertEquals(assertIs<JsonArray>(start["dynamicTools"]).toList(), target.tools)
        assertTrue(target.tools.any { it.text("name") == "read_file" })
        fixture.runtime.close()
    }

    @Test
    fun `protected creation refuses effective configuration the probe cannot reproduce`() = runTest {
        for (extra in listOf(
            "tools" to json("view_image" to JsonPrimitive(true)),
            "profile" to "danger".json(),
            "experimental_use_unified_exec_tool" to JsonPrimitive(true),
        )) {
            val fixture = Fixture(this, tools = SafetyTools())
            fixture.nativeConfig = JsonObject(fixture.nativeConfig + extra)
            var checks = 0
            fixture.wire.verifyInventory = { _, _ -> checks++ }
            assertFailsWith<EngineException>(extra.first) { fixture.open() }
            assertEquals(0, checks)
            assertTrue(fixture.requests("thread/start").isEmpty())
            fixture.runtime.close()
        }
    }

    @Test
    fun `transports without an offline probe refuse protected inventory`() = runTest {
        val wire = object : CodexWire {
            override val messages = kotlinx.coroutines.flow.emptyFlow<JsonObject>()
            override suspend fun write(message: JsonObject) = Unit
            override fun close() = Unit
        }
        val target = CodexInventoryTarget("model", json(), json(), emptyList())
        assertFailsWith<EngineException> { wire.verifyInventory(target, CodexNativeOff(isToolFree = true)) }
    }

    @Test
    fun `protected sessions disable agents explicitly to override resumed delegation`() {
        val off = CodexNativeOff(isActionAnalysisRequired = true)
        val locked = off.applyTo(json())
        assertTrue("agents.enabled=false" in off.arguments())
        assertEquals(JsonPrimitive(false), locked.obj("agents")["enabled"])
        val enabled = JsonObject(locked + ("agents" to json("enabled" to JsonPrimitive(true))))
        for ((effective, flags) in listOf(enabled to locked, locked to enabled)) {
            val layer = json("name" to json("type" to "sessionFlags".json()), "config" to flags)
            val response = json("config" to effective, "layers" to JsonArray(listOf(layer)))
            assertFailsWith<EngineException> { off.validateConfig(response) }
        }
    }

    @Test
    fun `unsafe inventory closes the candidate without creating a user thread`() = runTest {
        val fixture = Fixture(this, tools = SafetyTools())
        fixture.wire.verifyInventory = { _, _ -> inventoryFailure() }
        assertFailsWith<EngineException> { fixture.open() }
        assertTrue(fixture.requests("thread/start").isEmpty())
        assertTrue(fixture.wire.peers.all { it.isClosed })
        fixture.runtime.close()
    }

    @Test
    fun `tool free isolation requires the verified inventory and closes questions without action analysis`() {
        val off = CodexNativeOff(isToolFree = true)
        assertFalse(off.isActionAnalysisRequired)
        assertTrue(off.isVerifiedInventoryRequired)
        val locked = off.applyTo(json())
        assertEquals(JsonPrimitive(false), locked.obj("features")["default_mode_request_user_input"])
        assertTrue("features.default_mode_request_user_input=false" in off.arguments())
        val disabledFeatures = listOf(
            "view_image",
            "code_mode_only",
            "multi_agent_v2",
            "goals",
            "default_mode_request_user_input",
        )
        for (name in disabledFeatures) {
            assertEquals(JsonPrimitive(false), locked.obj("features")[name])
            val enabled = JsonObject(
                locked + ("features" to JsonObject(locked.obj("features") + (name to JsonPrimitive(true)))),
            )
            assertFailsWith<EngineException>(name) { off.validateConfig(configuration(enabled, locked)) }
            assertFailsWith<EngineException>(name) { off.validateConfig(configuration(locked, enabled)) }
        }
        off.validateConfig(configuration(locked, locked))
    }

    @Test
    fun `tool free sessions close the complete native catalog and reject unsolicited questions with the toggle off`() =
        runTest {
            val tools = SafetyTools().apply {
                required = false
                isToolFree = true
            }
            val fixture = Fixture(this, tools = tools)
            val session = fixture.open()
            val start = fixture.requests("thread/start").single().obj("params")
            assertRestricted(start)
            assertNull(start["dynamicTools"])
            assertEquals(JsonPrimitive(false), start.obj("config").obj("features")["default_mode_request_user_input"])
            assertTrue(fixture.wire.peers.single().off.isToolFree)
            assertFalse(fixture.wire.peers.single().off.isActionAnalysisRequired)
            session.feature(SendsPrompts).send(Prompt)
            // Even release of the policy cannot grant a text-only native turn permission to open a form.
            tools.isToolFree = false
            fixture.unsolicitedQuestion()
            runCurrent()
            assertIs<ActiveSessionState.Running>(session.state.value)
            assertEquals(JsonObject(emptyMap()), fixture.questionResponse().obj("answers"))
            fixture.runtime.close()
        }

    @Test
    fun `new tool free isolation cold resumes a legacy connection before submission`() = runTest {
        val tools = SafetyTools().apply { required = false }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        tools.isToolFree = true
        session.feature(SendsPrompts).send(Prompt)
        assertEquals(2, fixture.wire.peers.size)
        assertTrue(fixture.wire.peers.first().isClosed)
        val resume = fixture.requests("thread/resume").single().obj("params")
        assertRestricted(resume)
        assertEquals(JsonPrimitive(false), resume.obj("config").obj("features")["default_mode_request_user_input"])
        assertTrue(fixture.wire.origin(fixture.requests("turn/start").single()).off.isToolFree)
        fixture.runtime.close()
    }

    @Test
    fun `an active legacy turn refuses native questions after tool free isolation is installed`() = runTest {
        val tools = SafetyTools().apply { required = false }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        tools.isToolFree = true
        fixture.unsolicitedQuestion()
        runCurrent()
        assertIs<ActiveSessionState.Running>(session.state.value)
        assertEquals(JsonObject(emptyMap()), fixture.questionResponse().obj("answers"))
        fixture.runtime.close()
    }

    @Test
    fun `actual image and alternate execution flags must be off in both effective config and active layer`() {
        val off = CodexNativeOff(isActionAnalysisRequired = true)
        val locked = off.applyTo(json())
        for (name in listOf("view_image", "code_mode_only", "code_mode_host", "multi_agent_v2", "goals")) {
            assertEquals(JsonPrimitive(false), locked.obj("features")[name])
            assertTrue("features.$name=false" in off.arguments())
            // Keeping the old view_image_tool or multi_agent key Off is insufficient for this build.
            val enabled = JsonObject(
                locked + ("features" to JsonObject(locked.obj("features") + (name to JsonPrimitive(true)))),
            )
            assertFailsWith<EngineException>(name) { off.validateConfig(configuration(enabled, locked)) }
            assertFailsWith<EngineException>(name) { off.validateConfig(configuration(locked, enabled)) }
            val missing = JsonObject(locked + ("features" to JsonObject(locked.obj("features") - name)))
            assertFailsWith<EngineException>(name) { off.validateConfig(configuration(missing, locked)) }
        }
        off.validateConfig(configuration(locked, locked))
    }

    @Test
    fun `enhanced restrictions preserve authentication sandbox and non execution features`() {
        val base = json(
            "features" to json("secret_auth_storage" to JsonPrimitive(true), "fast_mode" to JsonPrimitive(true)),
            "sandbox_mode" to "read-only".json(),
            "model_provider" to "openai".json(),
        )
        val locked = CodexNativeOff(isActionAnalysisRequired = true).applyTo(base)
        assertEquals(base["sandbox_mode"], locked["sandbox_mode"])
        assertEquals(base["model_provider"], locked["model_provider"])
        assertEquals(JsonPrimitive(true), locked.obj("features")["secret_auth_storage"])
        assertEquals(JsonPrimitive(true), locked.obj("features")["fast_mode"])
    }

    @Test
    fun `enhanced creation disables ungated tools and preserves hosted declarations and native isolation`() = runTest {
        val tools = SafetyTools()
        val fixture = Fixture(this, tools = tools)
        val workspace = WorkspaceRef("project")
        fixture.workspacePaths[workspace] = "/project"
        val session = fixture.open(workspace)
        val start = fixture.requests("thread/start").single().obj("params")
        assertRestricted(start)
        val declarations = assertIs<JsonArray>(start["dynamicTools"]).map { assertIs<JsonObject>(it).text("name") }
        assertTrue("read_file" in declarations)
        assertTrue("web_search" in declarations)
        assertTrue(fixture.wire.peers.single().off.isActionAnalysisRequired)
        session.feature(SendsPrompts).send(Prompt)
        fixture.event(
            "item/tool/call",
            "turnId" to "native-turn".json(),
            "tool" to "read_file".json(),
            "arguments" to JsonObject(emptyMap()),
            id = JsonPrimitive(80),
        )
        runCurrent()
        assertEquals(1, tools.executions)
        fixture.runtime.close()
    }

    @Test
    fun `resumed sessions apply enhanced restrictions before any prompt`() = runTest {
        val fixture = Fixture(this, tools = SafetyTools())
        val ref = SessionRef(fixture.target.engine, fixture.environment.config.historySource, "thread")
        fixture.runtime.attach(ref, ResumeSessionRequest(fixture.target))
        assertRestricted(fixture.requests("thread/resume").single().obj("params"))
        assertTrue(fixture.requests("turn/start").isEmpty())
        fixture.runtime.close()
    }

    @Test
    fun `a different required analysis snapshot cold resumes the existing execution connection`() = runTest {
        val tools = SafetyTools().apply { required = false }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        // A production profile keeps its snapshot fixed; an adapter still rejects a stale cached connection.
        tools.required = true
        session.feature(SendsPrompts).send(Prompt)
        assertEquals(2, fixture.wire.peers.size)
        assertTrue(fixture.wire.peers.first().isClosed)
        assertRestricted(fixture.requests("thread/resume").single().obj("params"))
        assertTrue(fixture.wire.origin(fixture.requests("turn/start").single()).off.isActionAnalysisRequired)
        fixture.runtime.close()
    }

    @Test
    fun `an analysis snapshot changed during opening cannot publish a stale thread`() = runTest {
        val tools = SafetyTools().apply { required = false }
        val fixture = Fixture(this, tools = tools)
        val handler = fixture.wire.handler
        fixture.wire.handler = {
            if (it.text("method") == "config/read") tools.required = true
            handler(it)
        }
        assertFailsWith<EngineException> { fixture.open() }
        assertTrue(fixture.requests("thread/start").isEmpty())
        assertTrue(fixture.requests("turn/start").isEmpty())
        fixture.runtime.close()
    }

    @Test
    fun `final confirmation rejects a different action analysis snapshot without executing`() = runTest {
        val tools = SafetyTools().apply { required = false }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        var lookups = 0
        tools.beforePolicy = { if (++lookups == 2) tools.required = true }
        assertFailsWith<EngineException> {
            session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("rejected")))
        }
        assertEquals(1, fixture.requests("turn/start").size)
        assertIs<ActiveSessionState.Ready>(session.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `managed configuration cannot reenable an ungated native tool`() = runTest {
        val fixture = Fixture(this, tools = SafetyTools())
        val handler = fixture.wire.handler
        fixture.wire.handler = { message ->
            if (message.text("method") == "config/read") {
                val locked = fixture.wire.origin(message).off.applyTo(fixture.nativeConfig)
                fixture.wire.reply(
                    message,
                    json(
                        "config" to JsonObject(locked + ("web_search" to "live".json())),
                        "layers" to JsonArray(
                            listOf(json("name" to json("type" to "sessionFlags".json()), "config" to locked)),
                        ),
                    ),
                )
            } else {
                handler(message)
            }
        }
        assertFailsWith<EngineException> { fixture.open() }
        assertTrue(fixture.requests("thread/start").isEmpty())
        assertTrue(fixture.requests("turn/start").isEmpty())
        fixture.runtime.close()
    }

    private fun assertRestricted(params: JsonObject) {
        val config = params.obj("config")
        val disabledFeatures = listOf(
            "shell_tool", "apply_patch_freeform", "multi_agent", "view_image_tool", "view_image", "code_mode_only",
            "code_mode_host", "multi_agent_v2", "goals",
        )
        for (feature in disabledFeatures) {
            assertEquals(JsonPrimitive(false), config.obj("features")[feature])
        }
        assertEquals("disabled", config.text("web_search"))
        assertEquals("never", params.text("approvalPolicy"))
        assertEquals("read-only", params.text("sandbox"))
    }

    private suspend fun Fixture.unsolicitedQuestion() = event(
        "item/tool/requestUserInput",
        "turnId" to "native-turn".json(),
        "questions" to JsonArray(
            listOf(json("id" to "question".json(), "question" to "May the reviewer use tools?".json())),
        ),
        id = JsonPrimitive(81),
    )

    private fun Fixture.questionResponse(): JsonObject =
        wire.written.single { it["id"] == JsonPrimitive(81) }.obj("result")

    private fun Fixture.requests(method: String): List<JsonObject> = wire.written.filter { it.text("method") == method }

    private fun configuration(effective: JsonObject, flags: JsonObject): JsonObject = json(
        "config" to effective,
        "layers" to JsonArray(listOf(json("name" to json("type" to "sessionFlags".json()), "config" to flags))),
    )

    private class SafetyTools : ProfileAgentTools by NoAgentTools {
        var required = true
        var executions = 0
        var isToolFree = false
        var beforePolicy: () -> Unit = {}
        override suspend fun requiresActionAnalysis(): Boolean = required
        override suspend fun nativeToolsForExecution(scope: ToolPolicyScope): ResolvedToolPolicy {
            beforePolicy()
            return ResolvedToolPolicy(
                hostedDenied = if (isToolFree) setOf("read_file", "web_search", "web_fetch") else emptySet(),
                isToolFree = isToolFree,
            )
        }
        override suspend fun specifications(workspace: WorkspaceRef?) = if (isToolFree) {
            emptyList()
        } else {
            listOf(AgentToolSpec("read_file", "Read file through the host", json("type" to "object".json())))
        }
        override suspend fun specifications(scope: io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope) =
            specifications(scope.workspace)
        override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
            executions++
            return AgentToolResult("result")
        }
    }
}
