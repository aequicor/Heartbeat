package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeToolFlagsTest {
    @Test
    fun `each turn resolves policy against the session and removes newly disabled tools`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        var policy = ResolvedToolPolicy(nativeOn = setOf("Read"))
        val scopes = mutableListOf<ToolPolicyScope>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun nativeTools(scope: ToolPolicyScope): ResolvedToolPolicy {
                scopes += scope
                return policy
            }
        }
        val runtime = fixture.runtime(tools)
        val session = runtime.create(CreateSessionRequest(testTarget))
        session.features.available(SendsPrompts).send(prompt("first"))
        runCurrent()
        assertEquals(setOf("WebSearch"), claudeToolFlags(fixture.transport.calls.last()).native)
        policy = ResolvedToolPolicy(nativeOff = setOf("Read"), hostedDenied = setOf("web_search", "web_fetch"))
        session.features.available(SendsPrompts).send(prompt("next"))
        runCurrent()
        assertTrue(claudeToolFlags(fixture.transport.calls.last()).native.isEmpty())
        assertTrue(SEARCH_BRIDGE_MARKER !in fixture.transport.calls.last())
        assertTrue(scopes.all { it.session == session.ref && it.target?.engine == session.ref.engine })
        runtime.close()
    }

    @Test
    fun `native names never become preapprovals in any argument builder`() {
        val native = ClaudeNativeCatalog.mapTo(linkedSetOf()) { it.name }
        val flags = ClaudeToolFlags(native, ClaudeSearchTools.values.toSet())
        val base = claudeArguments(search = true, tools = flags)
        val config = Path.of("config.json")
        for (arguments in listOf(
            base,
            claudeSearchArguments(base, config, flags),
            claudeHostedArguments(base, config, Path.of("instructions.txt"), flags),
        )) {
            val effective = claudeToolFlags(arguments)
            assertEquals(native, effective.native)
            assertTrue(effective.allowed.all { it.startsWith("mcp__") })
            assertFalse(effective.allowed.any { it in native })
            assertEquals(1, arguments.count { it.startsWith("--tools=") })
            assertEquals(1, arguments.count { it.startsWith("--allowedTools=") })
        }
        for (name in native) assertFailsWith<IllegalArgumentException> { ClaudeToolFlags(allowed = setOf(name)) }
    }

    @Test
    fun `hosted builder preserves gated mode without adding dontAsk`() {
        val flags = ClaudeToolFlags(native = setOf("Bash"))
        val gateOptions = listOf("--permission-prompt-tool=stdio", "--permission-mode=default")
        val base = claudeArguments(tools = flags) + gateOptions
        val arguments = claudeHostedArguments(base, Path.of("config"), Path.of("prompt"), flags)
        assertEquals(listOf("--permission-mode=default"), arguments.filter { it.startsWith("--permission-mode=") })
        assertFalse("Bash" in claudeToolFlags(arguments).allowed)
    }

    @Test
    fun `off removes native tools and denies only the disabled MCP search operation`() {
        val flags = claudeToolFlags(
            ResolvedToolPolicy(
                nativeOn = setOf("Read", "Bash"),
                nativeOff = setOf("Read", "Agent"),
                hostedDenied = setOf("web_search"),
            ),
            search = true,
            subagents = true,
            providerSearch = true,
        )
        assertEquals(setOf("Bash", "Task", "TaskOutput", "TaskStop"), flags.native)
        assertEquals(setOf("mcp__heartbeat_search__web_fetch"), flags.allowed)
        assertTrue("--disallowedTools=mcp__heartbeat_search__web_search" in flags.arguments())
        val hosted = claudeHostedArguments(claudeArguments(tools = flags), Path.of("config"), Path.of("prompt"), flags)
        assertTrue("--disallowedTools=mcp__heartbeat_search__web_search" in hosted)
        assertFalse("WebSearch" in claudeToolFlags(hosted).native)
    }

    @Test
    fun `legacy optional tools require their own switches even if the policy catalog defaults include them`() {
        val policy = ResolvedToolPolicy(nativeOn = ClaudeAgentTools + "WebSearch")
        assertTrue(claudeToolFlags(policy, search = false, subagents = false, providerSearch = true).native.isEmpty())
        assertTrue(claudeToolFlags(policy, search = true, subagents = false, providerSearch = false).native.isEmpty())
        assertEquals(setOf("WebSearch"), claudeToolFlags(policy, true, false, true).native)
    }

    @Test
    fun `additional native tools are gated and default disabled while legacy tools permit Off only`() {
        val additional = ClaudeNativeCatalog.filter { it.isGated }
        assertEquals(
            setOf("Read", "Glob", "Grep", "Edit", "Write", "MultiEdit", "Bash", "WebFetch"),
            additional.map { it.name }.toSet(),
        )
        assertTrue(additional.none { it.isEnabledByDefault })
        assertTrue(ClaudeNativeCatalog.filterNot { it.isGated }.all { it.isEnabledByDefault })
    }
}
