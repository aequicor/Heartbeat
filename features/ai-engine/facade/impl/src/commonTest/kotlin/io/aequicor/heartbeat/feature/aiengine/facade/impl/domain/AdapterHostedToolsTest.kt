package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdapterHostedToolsTest {
    @Test
    fun `adapter tools are catalogued but never exposed or executed through generic hosted tools`() = runTest {
        val tools = DefaultAgentTools(setOf(Owner()))
        assertEquals(listOf("web_search"), tools.catalog().single().tools.map { it.name })
        assertTrue(tools.specifications(null as WorkspaceRef?).isEmpty())
        assertTrue(tools.execute(Context, "web_search", Arguments).isError)
        assertEquals(NativeVerdict.Allow, tools.authorizeHosted(Context, "web_search", Arguments))
        assertIs<NativeVerdict.Deny>(tools.authorizeHosted(Context, "unknown", Arguments))
        val ordinary = DefaultAgentTools(setOf(Owner(isAdapterOperated = false)))
        assertIs<NativeVerdict.Deny>(ordinary.authorizeHosted(Context, "web_search", Arguments))
    }

    @Test
    fun `hosted off and failures deny adapter calls before a hook can run`() = runTest {
        for (policy in listOf(null, ResolvedToolPolicy(hostedDenied = setOf("web_search")))) {
            val tools = DefaultAgentTools(
                setOf(Owner()),
                Hook { error("Off must be checked first") },
                ToolPolicyResolver { _, _, _ -> policy },
            )
            assertIs<NativeVerdict.Deny>(tools.authorizeHosted(Context, "web_search", Arguments))
        }
    }

    @Test
    fun `hosted hooks see original arguments and may ask or deny read at full trust`() = runTest {
        val owner = Owner()
        var verdict: ToolHookVerdict = ToolHookVerdict.Ask("Review query")
        val tools = DefaultAgentTools(
            setOf(owner),
            Hook {
                assertEquals(Arguments, it.arguments)
                assertFalse(it.isNative)
                assertEquals(AgentToolAction.Read, it.action)
                verdict
            },
        )
        var decisions = 0
        val context = Context.copy(
            permissions = AgentToolPermissions {
                decisions++
                assertTrue(it.description.orEmpty().contains("secret query"))
                assertTrue(it.description.orEmpty().contains("Review query"))
                true
            },
        )
        assertEquals(NativeVerdict.Allow, tools.authorizeHosted(context, "web_search", Arguments))
        verdict = ToolHookVerdict.Deny("Blocked")
        assertIs<NativeVerdict.Deny>(tools.authorizeHosted(context, "web_search", Arguments))
        assertEquals(1, decisions)
    }

    @Test
    fun `permission does not outlive policy or contribution availability`() = runTest {
        for (isPolicyOff in listOf(true, false)) {
            val owner = Owner()
            var policy = ResolvedToolPolicy()
            val tools = DefaultAgentTools(
                setOf(owner),
                Hook { ToolHookVerdict.Ask("Review") },
                ToolPolicyResolver { _, _, _ -> policy },
            )
            val context = Context.copy(
                permissions = AgentToolPermissions {
                    if (isPolicyOff) {
                        policy = ResolvedToolPolicy(
                            hostedDenied = setOf("web_search"),
                        )
                    } else {
                        owner.isEnabled = false
                    }
                    true
                },
            )
            assertIs<NativeVerdict.Deny>(tools.authorizeHosted(context, "web_search", Arguments))
        }
    }

    @Test
    fun `the facade turn barrier revokes pending adapter approvals`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val tools = DefaultAgentTools(setOf(Owner()), Hook { ToolHookVerdict.Ask("Wait") })
        val context = Context.copy(
            permissions = AgentToolPermissions {
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        val waiting = async { tools.authorizeHosted(context, "web_search", Arguments) }
        entered.await()
        tools.finishTurn(context.session, context.turn)
        assertFailsWith<CancellationException> { waiting.await() }
        assertIs<NativeVerdict.Deny>(tools.authorizeHosted(context, "web_search", Arguments))
    }
}

private class Owner(override val isAdapterOperated: Boolean = true) : AgentToolContribution {
    var isEnabled = true
    override val isDetachedSupported = true
    private val spec = AgentToolSpec("web_search", "Search", JsonObject(emptyMap()), action = AgentToolAction.Read)
    override val catalog = listOf(spec).toolCatalog()
    override suspend fun specifications(workspace: WorkspaceRef?) = if (isEnabled) listOf(spec) else emptyList()
    override fun approval(spec: AgentToolSpec, arguments: JsonObject) = AgentToolApproval(
        spec.name,
        "Search",
        arguments.toString(),
    )
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        error("Adapter owns execution")
}

private class Hook(private val verdict: (HookedToolCall) -> ToolHookVerdict) : SessionHooks {
    override fun context(session: SessionRef, request: RequestId?, turn: TurnId?) =
        SessionHookContext(session, null, request, turn, SessionOwner("test"))
    override suspend fun beforeTool(call: HookedToolCall) = verdict(call)
}

private val Arguments = JsonObject(mapOf("query" to JsonPrimitive("secret query")))
private val Context = AgentToolContext(
    SessionRef(EngineId("pi"), SessionSourceId("test"), "session"),
    null,
    TurnId("turn"),
    RequestId("request"),
    TrustLevel.Full,
    AgentToolPermissions { error("No permission expected") },
)
