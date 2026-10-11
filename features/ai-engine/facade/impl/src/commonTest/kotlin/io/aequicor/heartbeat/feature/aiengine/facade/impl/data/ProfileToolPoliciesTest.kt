package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
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
import kotlinx.coroutines.flow.flowOf
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
    fun `native opt in needs both toggle and workspace while off works without either`() = runTest {
        val policy = ToolPolicy(native = mapOf("read" to ToolSwitch.Off, "edit" to ToolSwitch.On))
        val disabled = resolver(listOf(policy), isEnabled = false).resolve(scope, emptySet(), false)!!
        val detached = resolver(listOf(policy)).resolve(scope.copy(workspace = null), emptySet(), false)!!
        assertTrue(disabled.nativeOn.isEmpty())
        assertTrue(detached.nativeOn.isEmpty())
        assertEquals(setOf("read"), disabled.nativeOff)
        assertEquals(disabled.nativeOff, detached.nativeOff)
        assertFalse(HarnessNativeTools.default)
    }

    @Test
    fun `equal effective policies retain generation but an ABA change gets a new generation`() = runTest {
        var current = ToolPolicy()
        val resolver = create(lazyOf(setOf(AgentToolPolicyProvider { current })))
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
        val resolver = create(lazyOf(setOf(AgentToolPolicyProvider { error("private provider detail") })))
        val declarations = resolver.resolve(scope, emptySet(), false)!!
        assertEquals(setOf("read"), declarations.nativeOn)
        assertTrue(declarations.hostedDenied.isEmpty())
        assertNull(resolver.resolve(scope, emptySet(), true))
    }

    @Test
    fun `already reported provider unavailability still fails closed`() = runTest {
        val resolver = create(lazyOf(setOf(AgentToolPolicyProvider { throw ToolPolicyUnavailable() })))
        val declarations = resolver.resolve(scope, emptySet(), false)!!
        assertEquals(setOf("read"), declarations.nativeOn)
        assertNull(resolver.resolve(scope, emptySet(), true))
    }

    @Test
    fun `adapter preflight cannot restore native defaults after its restrictive provider fails`() = runTest {
        var isFailed = false
        val provider = AgentToolPolicyProvider {
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
            policies = create(lazyOf(setOf(AgentToolPolicyProvider { awaitCancellation() }))),
        )
        assertNull(stalled.nativeToolsForExecution(scope))
        val cancelled = DefaultAgentTools(
            emptySet(),
            policies = create(lazyOf(setOf(AgentToolPolicyProvider { throw CancellationException("cancel") }))),
        )
        assertFailsWith<CancellationException> { cancelled.nativeToolsForExecution(scope) }
    }

    @Test
    fun `provider timeout refuses execution while caller cancellation propagates`() = runTest {
        val stalled = create(lazyOf(setOf(AgentToolPolicyProvider { awaitCancellation() })))
        assertNull(stalled.resolve(scope, emptySet(), true))
        val cancelled = create(lazyOf(setOf(AgentToolPolicyProvider { throw CancellationException("cancel") })))
        assertFailsWith<CancellationException> { cancelled.resolve(scope, emptySet(), false) }
    }

    @Test
    fun `providers are lazy and receive the exact trusted session scope`() = runTest {
        var isLoaded = false
        var received: ToolPolicyScope? = null
        val contributions = lazy {
            isLoaded = true
            setOf(
                AgentToolPolicyProvider {
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
                    AgentToolPolicyProvider {
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
                    AgentToolPolicyProvider {
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
    fun `native enable toggle changes effective generation`() = runTest {
        var isEnabled = false
        val toggles = object : FeatureToggles {
            override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(toggle.default)
            override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T {
                @Suppress("UNCHECKED_CAST")
                return if (toggle == HarnessNativeTools) isEnabled as T else toggle.default
            }
        }
        val provider = AgentToolPolicyProvider { ToolPolicy(native = mapOf("edit" to ToolSwitch.On)) }
        val resolver = create(lazyOf(setOf(provider)), toggles)
        val before = resolver.resolve(scope, emptySet(), false)!!
        isEnabled = true
        val after = resolver.resolve(scope, emptySet(), false)!!
        assertEquals(setOf("read"), before.nativeOn)
        assertEquals(setOf("read", "edit"), after.nativeOn)
        assertNotEquals(before.generation, after.generation)
    }

    private fun session(id: String) = SessionRef(TestEngine, SessionSourceId("local"), id)

    private fun resolver(policies: List<ToolPolicy>, isEnabled: Boolean = true) = create(
        lazyOf(policies.map { policy -> AgentToolPolicyProvider { policy } }.toSet()),
        flags(isEnabled),
    )

    private fun create(
        providers: Lazy<Set<AgentToolPolicyProvider>>,
        toggles: FeatureToggles = flags(true),
    ): ProfileToolPolicies {
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
        return ProfileToolPolicies(providers, registry, toggles)
    }

    private fun flags(isEnabled: Boolean) = object : FeatureToggles {
        override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(toggle.default)
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T {
            @Suppress("UNCHECKED_CAST")
            return if (toggle == HarnessNativeTools) isEnabled as T else toggle.default
        }
    }
}
