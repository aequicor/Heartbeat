package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClaudeActionSafetyTest {
    @Test
    fun `enhanced flags retain intercepted native tools and scoped search instead of provider search`() {
        val selected = ClaudeNativeCatalog.map { it.name }.toSet()
        val flags = claudeToolFlags(
            ResolvedToolPolicy(nativeOn = selected),
            search = true,
            subagents = true,
            providerSearch = true,
            requiresActionAnalysis = true,
        )
        assertEquals(ClaudeNativeCatalog.filter { it.isGated }.map { it.name }.toSet(), flags.native)
        assertEquals(ClaudeSearchTools.values.toSet(), flags.allowed)
        assertFalse("WebSearch" in flags.allowed)
    }

    @Test
    fun `enhanced turns keep hosted tools and bind search to the captured turn guard`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.versionValue = "2.1.285"
        val bridge = Bridge()
        val runtime = fixture.runtime(SafetyTools(), bridge)
        val session = runtime.create(CreateSessionRequest(testTarget, WorkspaceRef("project")))
        session.features.available(SendsPrompts).send(prompt())
        runCurrent()
        assertEquals(1, bridge.attached)
        assertNotNull(fixture.transport.hostedCalls.last()?.endpoint)
        assertNotNull(fixture.transport.hostedCalls.last()?.searchGuard)
        assertRestricted(fixture.transport.calls.last())
        runtime.close()
    }

    @Test
    fun `each resumed turn reapplies the profile action analysis requirement`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.versionValue = "2.1.285"
        val tools = SafetyTools().apply { required = false }
        val first = fixture.runtime(tools)
        val session = first.create(CreateSessionRequest(testTarget))
        session.features.available(SendsPrompts).send(prompt())
        runCurrent()
        assertTrue(SEARCH_BRIDGE_MARKER in fixture.transport.calls.last())
        first.close()
        // A new profile lifetime may select the enhanced mode for an existing native conversation.
        tools.required = true
        val second = fixture.runtime(tools)
        val resumed = second.attach(session.ref, ResumeSessionRequest(testTarget))
        resumed.features.available(SendsPrompts).send(prompt("next"))
        runCurrent()
        val arguments = fixture.transport.calls.last()
        assertTrue("--resume=${session.ref.nativeId}" in arguments)
        assertRestricted(arguments)
        resumed.features.available(SendsPrompts).send(prompt("another"))
        runCurrent()
        assertRestricted(fixture.transport.calls.last())
        second.close()
    }

    @Test
    fun `unknown unsupported CLI and unavailable strict policy cannot start an enhanced turn`() = runTest {
        for (version in listOf(null, "2.1.284", "2.1.285")) {
            val fixture = ClaudeFixture(backgroundScope)
            fixture.transport.versionValue = version
            val tools = SafetyTools().apply { isPolicyAvailable = version != "2.1.285" }
            val runtime = fixture.runtime(tools)
            val session = runtime.create(CreateSessionRequest(testTarget))
            val before = fixture.transport.calls.size
            val failure = assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
            assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), failure.failure)
            // Account validation is read-only preparation; no prompt process may start after it.
            assertEquals(listOf(listOf("auth", "status")), fixture.transport.calls.drop(before))
            runtime.close()
        }
    }

    @Test
    fun `a different policy snapshot cannot use a prepared launch plan`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.versionValue = "2.1.285"
        val tools = SafetyTools().apply { changeAfterCapture = true }
        val runtime = fixture.runtime(tools)
        val session = runtime.create(CreateSessionRequest(testTarget))
        val before = fixture.transport.calls.size
        val failure = assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), failure.failure)
        assertEquals(listOf(listOf("auth", "status")), fixture.transport.calls.drop(before))
        runtime.close()
    }

    private fun assertRestricted(arguments: List<String>) {
        assertTrue(SEARCH_BRIDGE_MARKER in arguments)
        assertTrue(SCOPED_SEARCH_BRIDGE_MARKER in arguments)
        assertTrue(claudeToolFlags(arguments).native.isEmpty())
        assertEquals(ClaudeSearchTools.values.toSet(), claudeToolFlags(arguments).allowed)
    }

    private class SafetyTools : ProfileAgentTools by NoAgentTools {
        var required = true
        var isPolicyAvailable = true
        var changeAfterCapture = false
        override suspend fun requiresActionAnalysis(): Boolean = required.also {
            if (changeAfterCapture) required = !required
        }
        override suspend fun nativeToolsForExecution(scope: ToolPolicyScope): ResolvedToolPolicy? =
            ResolvedToolPolicy().takeIf { isPolicyAvailable }
        override suspend fun specifications(workspace: WorkspaceRef?) = listOf(
            AgentToolSpec("read_file", "Read through the shared gate", JsonObject(emptyMap())),
        )
        override suspend fun specifications(scope: AgentToolScope) = specifications(scope.workspace)
    }

    private class Bridge : AgentToolBridge {
        var attached = 0
        override val isAvailable = true
        override suspend fun attach(
            workspace: WorkspaceRef?,
            context: suspend () -> AgentToolContext?,
        ): AgentToolBridgeAttachment {
            attached++
            return object : AgentToolBridgeAttachment {
                override val endpoint = AgentToolBridgeEndpoint("http://127.0.0.1:42", "test-token")
                override fun close() = Unit
            }
        }
    }
}
