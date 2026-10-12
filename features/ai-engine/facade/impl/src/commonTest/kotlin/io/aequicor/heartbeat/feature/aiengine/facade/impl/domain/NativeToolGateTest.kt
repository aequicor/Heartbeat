package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class NativeToolGateTest {
    @Test
    fun `off and unknown tools are denied before hooks coverage or permission`() = runTest {
        val hook = Hook()
        val tools = fixture(
            hook,
            ToolPolicyResolver { _, _, _ ->
                ResolvedToolPolicy(nativeOn = setOf("read"), nativeOff = setOf("read"))
            },
        )
        val forbiddenCoverage: (TrustLevel) -> Boolean = { error("Off must run first") }
        assertIs<NativeVerdict.Deny>(tools.authorizeNative(CONTEXT, call(covered = forbiddenCoverage)))
        assertIs<NativeVerdict.Deny>(tools.authorizeNative(CONTEXT, call("unknown", forbiddenCoverage)))
        assertTrue(hook.calls.isEmpty())
    }

    @Test
    fun `native hook receives null turn and ask forces permission even for full trust read`() = runTest {
        val hook = Hook(ToolHookVerdict.Ask("Review this call"))
        val tools = fixture(hook)
        var asked = 0
        val context = CONTEXT.copy(
            permissions = AgentToolPermissions {
                asked++
                assertTrue(it.description.orEmpty().contains("Review this call"))
                assertTrue(it.description.orEmpty().contains("/project/file"))
                true
            },
        )
        val call = call().copy(paths = listOf("/project/file"))
        assertEquals(NativeVerdict.Allow, tools.authorizeNative(context, call))
        assertEquals(1, asked)
        assertTrue(hook.calls.single().isNative)
        assertNull(hook.calls.single().context.turn)
        assertEquals(REQUEST, hook.calls.single().context.request)
    }

    @Test
    fun `hook denial dominates full trust and no permission is requested`() = runTest {
        val tools = fixture(Hook(ToolHookVerdict.Deny("Blocked")))
        val result = tools.authorizeNative(CONTEXT, call())
        assertTrue(assertIs<NativeVerdict.Deny>(result).reason.contains("Blocked by a session hook"))
    }

    @Test
    fun `adapter coverage determines whether ordinary approval is required`() = runTest {
        val tools = fixture()
        var asked = 0
        val context = CONTEXT.copy(
            permissions = AgentToolPermissions {
                asked++
                false
            },
        )
        assertEquals(NativeVerdict.Allow, tools.authorizeNative(context, call()))
        assertIs<NativeVerdict.Deny>(tools.authorizeNative(context, call(covered = { false })))
        assertEquals(1, asked)
    }

    @Test
    fun `off or provider failure while waiting for permission prevents native allow`() = runTest {
        for (isFailure in listOf(false, true)) {
            var policy: ResolvedToolPolicy? = ENABLED
            val tools = fixture(policies = ToolPolicyResolver { _, _, _ -> policy })
            val context = CONTEXT.copy(
                permissions = AgentToolPermissions {
                    policy = if (isFailure) null else ResolvedToolPolicy(nativeOff = setOf("read"))
                    true
                },
            )
            assertIs<NativeVerdict.Deny>(tools.authorizeNative(context, call(covered = { false })))
        }
    }

    @Test
    fun `finish barrier cancels native authorization and rejects delayed requests on the facade turn`() = runTest {
        val tools = fixture()
        val entered = CompletableDeferred<Unit>()
        val context = CONTEXT.copy(
            permissions = AgentToolPermissions {
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        val pending = async { tools.authorizeNative(context, call(covered = { false })) }
        entered.await()
        tools.finishTurn(SESSION, TURN)
        assertFailsWith<CancellationException> { pending.await() }
        assertIs<NativeVerdict.Deny>(tools.authorizeNative(CONTEXT, call()))
    }

    @Test
    fun `native turn lifetime independently revokes a pending permission`() = runTest {
        val tools = fixture()
        val entered = CompletableDeferred<Unit>()
        val lifetime = Job()
        val context = CONTEXT.copy(
            lifetime = lifetime,
            permissions = AgentToolPermissions {
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        val pending = async { tools.authorizeNative(context, call(covered = { false })) }
        entered.await()
        lifetime.cancel()
        assertFailsWith<CancellationException> { pending.await() }
    }

    @Test
    fun `approval never truncates command details and diagnostics redact the command`() = runTest {
        val tools = fixture()
        val long = call(covered = { false }).copy(command = "private".repeat(2_000))
        assertIs<NativeVerdict.Deny>(tools.authorizeNative(CONTEXT, long))
        assertEquals("NativeToolCall", long.toString())
        assertEquals("NativeVerdict.Deny", NativeVerdict.Deny("private").toString())
    }

    @Test
    fun `preflight is noninteractive and its one shot continuation does not repeat hooks`() = runTest {
        val hook = Hook(ToolHookVerdict.Ask("Review this call"))
        val tools = fixture(hook)
        var asked = 0
        val context = CONTEXT.copy(
            permissions = AgentToolPermissions {
                asked++
                true
            },
        )
        val input = kotlinx.serialization.json.Json.parseToJsonElement("""{"secret":"full-input"}""")
            as kotlinx.serialization.json.JsonObject
        val prepared = assertIs<NativePreparation.Ask>(tools.prepareNative(context, call().copy(arguments = input)))
        assertEquals(0, asked)
        assertEquals(input, hook.calls.single().arguments)
        assertEquals(NativeVerdict.Allow, prepared.confirmation.authorize())
        assertIs<NativeVerdict.Deny>(prepared.confirmation.authorize())
        assertEquals(1, hook.calls.size)
        assertEquals(1, asked)
    }

    @Test
    fun `policy change between preflight and approval denies without showing permission`() = runTest {
        var policy = ENABLED
        val tools = fixture(policies = ToolPolicyResolver { _, _, _ -> policy })
        val prepared = assertIs<NativePreparation.Ask>(tools.prepareNative(CONTEXT, call(covered = { false })))
        policy = ResolvedToolPolicy(nativeOff = setOf("read"))
        assertIs<NativeVerdict.Deny>(prepared.confirmation.authorize())
    }

    @Test
    fun `finished facade turn revokes a prepared confirmation before it is consumed`() = runTest {
        val tools = fixture()
        val prepared = assertIs<NativePreparation.Ask>(tools.prepareNative(CONTEXT, call(covered = { false })))
        tools.finishTurn(SESSION, TURN)
        assertIs<NativeVerdict.Deny>(prepared.confirmation.authorize())
    }

    @Test
    fun `preflight preserves trust coverage and never translates continue directly to allow`() = runTest {
        val tools = fixture()
        assertEquals(NativePreparation.Allow, tools.prepareNative(CONTEXT, call()))
        assertIs<NativePreparation.Ask>(tools.prepareNative(CONTEXT, call(covered = { false })))
    }

    private suspend fun TestScope.fixture(
        hook: Hook = Hook(),
        policies: ToolPolicyResolver = ToolPolicyResolver { _, _, _ -> ENABLED },
    ): DefaultAgentTools {
        val hooks = testSessionHooks(hook)
        hooks.observe(SessionLifecycle.Opened(HOOK_CONTEXT))
        hooks.bindTurn(HOOK_CONTEXT)
        return DefaultAgentTools(emptySet(), hooks, policies).also { it.bindTurn(SESSION, REQUEST, TURN) }
    }

    private class Hook(var verdict: ToolHookVerdict = ToolHookVerdict.Continue) : SessionHook {
        val calls = mutableListOf<HookedToolCall>()
        override val isIntercepting = true
        override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict {
            calls += call
            return verdict
        }
    }
}

private val SESSION = SessionRef(EngineId("test"), SessionSourceId("local"), "session")
private val REQUEST = RequestId("request")
private val TURN = TurnId("facade")
private val WORKSPACE = WorkspaceRef("project")
private val HOOK_CONTEXT = SessionHookContext(SESSION, WORKSPACE, REQUEST, TURN, SessionOwner("owner"))
private val CONTEXT = AgentToolContext(
    SESSION,
    WORKSPACE,
    TurnId("native"),
    REQUEST,
    TrustLevel.Full,
    permissions = AgentToolPermissions { error("No permission expected") },
)
private val ENABLED = ResolvedToolPolicy(nativeOn = setOf("read"))
private fun call(name: String = "read", covered: (TrustLevel) -> Boolean = { true }) =
    NativeToolCall(name, AgentToolAction.Read, covered = covered)
