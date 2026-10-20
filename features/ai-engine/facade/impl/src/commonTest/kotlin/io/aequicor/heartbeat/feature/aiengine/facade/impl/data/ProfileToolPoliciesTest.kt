package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyUnavailable
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.TestEngine
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.registration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class ProfileToolPoliciesTest {
    private val scope = ToolPolicyScope(engine = TestEngine, workspace = WorkspaceRef("project"))
    private val catalog = listOf(
        NativeToolSpec("read", AgentToolAction.Read, isEnabledByDefault = true, isGated = true),
        NativeToolSpec("edit", AgentToolAction.Edit, isEnabledByDefault = false, isGated = true),
        NativeToolSpec("shell", AgentToolAction.Command, isEnabledByDefault = false, isGated = false),
        NativeToolSpec("collision", AgentToolAction.Read, isEnabledByDefault = false, isGated = true),
    )

    @Test
    fun `off wins across providers and only gated known noncolliding names can be enabled`() = runTest {
        val policies = resolver(
            listOf(
                ToolPolicy(native = mapOf("read" to ToolSwitch.On, "edit" to ToolSwitch.On)),
                ToolPolicy(
                    hostedOff = setOf("mcp__heartbeat_tools__read_file", "web_fetch"),
                    native = mapOf(
                        "read" to ToolSwitch.Off,
                        "shell" to ToolSwitch.On,
                        "collision" to ToolSwitch.On,
                        "unknown" to ToolSwitch.On,
                    ),
                ),
            ),
        )
        val effective = policies.resolve(scope, setOf("collision"), isExecuting = true)!!
        assertEquals(setOf("edit"), effective.nativeOn)
        assertEquals(setOf("read"), effective.nativeOff)
        assertEquals(setOf("read_file", "web_fetch"), effective.hostedDenied)
    }

    @Test
    fun `gated tools are available without the obsolete toggle or a workspace`() = runTest {
        val disabled = resolver(emptyList()).resolve(scope, emptySet(), false)!!
        val detached = resolver(emptyList()).resolve(scope.copy(workspace = null), emptySet(), false)!!
        assertEquals(setOf("read", "edit", "collision"), disabled.nativeOn)
        assertEquals(disabled.nativeOn, detached.nativeOn)
        assertTrue(disabled.nativeOff.isEmpty())
    }

    @Test
    fun `equal effective policies retain generation but an ABA change gets a new generation`() = runTest {
        var current = ToolPolicy()
        val resolver = create(lazyOf(setOf(isolation { current })))
        val first = resolver.resolve(scope, emptySet(), false)!!
        current = ToolPolicy(native = mapOf("unknown" to ToolSwitch.On))
        assertEquals(first, resolver.resolve(scope, emptySet(), false))
        current = ToolPolicy(native = mapOf("read" to ToolSwitch.Off))
        val second = resolver.resolve(scope, emptySet(), false)!!
        current = ToolPolicy()
        val third = resolver.resolve(scope, emptySet(), false)!!
        assertEquals(first.copy(generation = third.generation), third)
        assertTrue(first.generation < second.generation && second.generation < third.generation)
    }

    @Test
    fun `provider failure restores defaults for declarations but refuses execution`() = runTest {
        val resolver = create(lazyOf(setOf(isolation { error("private provider detail") })))
        val declarations = resolver.resolve(scope, emptySet(), false)!!
        assertEquals(setOf("read"), declarations.nativeOn)
        assertTrue(declarations.hostedDenied.isEmpty())
        assertNull(resolver.resolve(scope, emptySet(), true))
    }

    @Test
    fun `already reported provider unavailability still fails closed`() = runTest {
        val resolver = create(lazyOf(setOf(isolation { throw ToolPolicyUnavailable() })))
        val declarations = resolver.resolve(scope, emptySet(), false)!!
        assertEquals(setOf("read"), declarations.nativeOn)
        assertNull(resolver.resolve(scope, emptySet(), true))
    }

    @Test
    fun `adapter preflight cannot restore native defaults after its restrictive provider fails`() = runTest {
        var isFailed = false
        val provider = isolation {
            if (isFailed) error("private provider detail")
            ToolPolicy(native = mapOf("read" to ToolSwitch.Off))
        }
        val tools = DefaultAgentTools(emptySet(), policies = create(lazyOf(setOf(provider))))
        val before = tools.nativeToolsForExecution(scope)!!
        assertEquals(setOf("read"), before.nativeOff)
        isFailed = true
        assertNull(tools.nativeToolsForExecution(scope))
        assertEquals(setOf("read"), tools.nativeTools(scope).nativeOn)
        assertNull(tools.nativeToolsForExecution(scope))
        isFailed = false
        val recovered = tools.nativeToolsForExecution(scope)!!
        assertEquals(before.nativeOff, recovered.nativeOff)
        assertTrue(recovered.generation > before.generation)
    }

    @Test
    fun `adapter preflight refuses a timed out provider and propagates cancellation`() = runTest {
        val stalled = DefaultAgentTools(
            emptySet(),
            policies = create(lazyOf(setOf(isolation { awaitCancellation() }))),
        )
        assertNull(stalled.nativeToolsForExecution(scope))
        val cancelled = DefaultAgentTools(
            emptySet(),
            policies = create(lazyOf(setOf(isolation { throw CancellationException("cancel") }))),
        )
        assertFailsWith<CancellationException> { cancelled.nativeToolsForExecution(scope) }
    }

    @Test
    fun `provider timeout refuses execution while caller cancellation propagates`() = runTest {
        val stalled = create(lazyOf(setOf(isolation { awaitCancellation() })))
        assertNull(stalled.resolve(scope, emptySet(), true))
        val cancelled = create(lazyOf(setOf(isolation { throw CancellationException("cancel") })))
        assertFailsWith<CancellationException> { cancelled.resolve(scope, emptySet(), false) }
    }

    @Test
    fun `providers are lazy and receive the exact trusted session scope`() = runTest {
        var isLoaded = false
        var received: ToolPolicyScope? = null
        val contributions = lazy {
            isLoaded = true
            setOf(
                isolation {
                    received = it
                    null
                },
            )
        }
        val resolver = create(contributions)
        assertFalse(isLoaded)
        resolver.resolve(scope, emptySet(), false)
        assertEquals(scope, received)
        assertTrue(isLoaded)
    }

    @Test
    fun `slow providers of one session do not serialize lookups of other sessions`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var entered = 0
        val resolver = create(
            lazyOf(
                setOf(
                    isolation {
                        entered++
                        gate.await()
                        null
                    },
                ),
            ),
        )
        val lookups = listOf("one", "two").map { id ->
            async { resolver.resolve(scope.copy(session = session(id)), emptySet(), false) }
        }
        runCurrent()
        assertEquals(2, entered)
        gate.complete(Unit)
        assertTrue(lookups.awaitAll().all { it != null })
    }

    @Test
    fun `a lookup that started earlier cannot replace a newer snapshot`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val resolver = create(
            lazyOf(
                setOf(
                    isolation {
                        if (++calls == 1) {
                            gate.await()
                            ToolPolicy(native = mapOf("read" to ToolSwitch.Off))
                        } else {
                            ToolPolicy()
                        }
                    },
                ),
            ),
        )
        val stale = async { resolver.resolve(scope, emptySet(), false)!! }
        runCurrent()
        val newer = resolver.resolve(scope, emptySet(), false)!!
        gate.complete(Unit)
        val outdated = stale.await()
        assertEquals(setOf("read"), outdated.nativeOff)
        assertNotEquals(newer.generation, outdated.generation)
        assertEquals(newer, resolver.resolve(scope, emptySet(), false))
    }

    @Test
    fun `remembered scopes are bounded for a long lived profile`() = runTest {
        val resolver = create(lazyOf(emptySet()))
        repeat(300) { resolver.resolve(scope.copy(session = session("session-$it")), emptySet(), false) }
        assertEquals(256, resolver.snapshotCount())
    }

    @Test
    fun `legacy policy providers cannot hide tools or fail their availability`() = runTest {
        val provider = AgentToolPolicyProvider { error("Legacy provider must not be consulted") }
        val result = create(lazyOf(setOf(provider))).resolve(scope, emptySet(), true)!!
        assertEquals(setOf("read", "edit", "collision"), result.nativeOn)
        assertTrue(result.hostedDenied.isEmpty())
    }

    private fun session(id: String) = SessionRef(TestEngine, SessionSourceId("local"), id)

    private fun resolver(policies: List<ToolPolicy>) = create(
        lazyOf(policies.map { policy -> isolation { policy } }.toSet()),
    )

    private fun create(providers: Lazy<Set<AgentToolPolicyProvider>>): ProfileToolPolicies {
        val registration = registration()
        val registry = EngineRegistry(
            listOf(
                EngineRegistration(
                    registration.descriptor.copy(nativeTools = catalog),
                    registration.authOwner,
                    registration.factory,
                ),
            ),
            EnginePlatform.DesktopMacOs,
        )
        return ProfileToolPolicies(providers, registry)
    }
}

private fun isolation(block: suspend (ToolPolicyScope) -> ToolPolicy?): AgentToolPolicyProvider =
    object : AgentToolPolicyProvider {
        override val isIsolation = true
        override suspend fun policy(scope: ToolPolicyScope): ToolPolicy? = block(scope)
    }
