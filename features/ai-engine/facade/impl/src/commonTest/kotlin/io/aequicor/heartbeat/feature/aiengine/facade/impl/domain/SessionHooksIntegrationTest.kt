package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHooksIntegrationTest {
    @Test
    fun `opt-in appends raw context only to the last text part`() = runTest {
        for (isEnabled in listOf(false, true)) {
            val fixture = HookFixture(this)
            val native = FakeNativeSession()
            val session = fixture.open(native, isEnabled)
            val parts = listOf(ContentPart.Text("first"), ContentPart.Text("last"))
            session.send(PromptRequest(RequestId("request"), parts))
            val expected = if (isEnabled) parts.dropLast(1) + ContentPart.Text("last\n\n<<<context>>>") else parts
            assertEquals(expected, native.sent.single().parts)
            assertEquals(if (isEnabled) listOf("first\nlast") else emptyList(), fixture.hook.prompts)
        }
    }

    @Test
    fun `slash commands and busy sessions do not invoke prompt hooks`() = runTest {
        val fixture = HookFixture(this)
        val native = FakeNativeSession()
        val session = fixture.open(native)
        session.send(prompt("/compact"))
        assertEquals("/compact", (native.sent.single().parts.single() as ContentPart.Text).text)
        assertFailsWith<EngineException> { session.send(prompt("busy")) }
        assertTrue(fixture.hook.prompts.isEmpty())
    }

    @Test
    fun `a turn finished before send returns is observed once in order`() = runTest {
        val fixture = HookFixture(this)
        val native = FakeNativeSession()
        native.onAccepted = { native.finish() }
        val session = fixture.open(native)
        val turn = session.send(prompt("run"))
        runCurrent()
        assertEquals(listOf("Opened", "TurnStarted", "TurnFinished"), fixture.hook.events.map { it::class.simpleName })
        assertEquals(turn, fixture.hook.events.last().context.turn)
        fixture.tools.finishTurn(session.ref, turn)
        assertNull(fixture.hooks.context(session.ref, RequestId("run"), turn))
    }

    @Test
    fun `a second handle cannot observe another handle's permissions or completion`() = runTest {
        val fixture = HookFixture(this)
        val native = FakeNativeSession()
        val first = fixture.open(native)
        val turn = first.send(prompt("run"))
        fixture.open(native)
        native.ask("permission")
        runCurrent()
        native.finish()
        runCurrent()
        val turns = fixture.hook.events.filter { it !is SessionLifecycle.Opened }
        assertEquals(listOf("TurnStarted", "PermissionRequested", "TurnFinished"), turns.map { it::class.simpleName })
        assertTrue(turns.all { it.context.turn == turn && it.context.owner.value == "handle1" })
    }

    @Test
    fun `permission observed before native send returns follows acceptance`() = runTest {
        val fixture = HookFixture(this)
        val native = FakeNativeSession()
        native.onAccepted = { native.ask("early") }
        fixture.open(native).send(prompt("run"))
        runCurrent()
        assertEquals(
            listOf("Opened", "TurnStarted", "PermissionRequested"),
            fixture.hook.events.map { it::class.simpleName },
        )
    }

    @Test
    fun `hook denial applies to hosted calls before native acceptance returns`() = runTest {
        val fixture = HookFixture(this)
        fixture.hook.verdict = ToolHookVerdict.Deny("blocked")
        val native = FakeNativeSession()
        var result: AgentToolResult? = null
        native.onAccepted = { result = fixture.execute(native) }
        fixture.open(native).send(prompt("run"))
        assertTrue(result!!.isError)
        assertTrue(result!!.text.startsWith("Blocked by a session hook"))
        assertEquals(0, fixture.owner.calls)
    }

    @Test
    fun `hook ask at Full preserves original approval binding and prefixes the result note`() = runTest {
        val fixture = HookFixture(this)
        fixture.hook.verdict = ToolHookVerdict.Ask("review")
        val native = FakeNativeSession()
        fixture.open(native).send(prompt("run"))
        var shown: AgentToolApproval? = null
        val result = fixture.execute(
            native,
            AgentToolPermissions {
                shown = it
                true
            },
        )
        assertFalse(result.isError)
        assertEquals("note\n\nresult", result.text)
        assertTrue(shown!!.description!!.contains("review"))
        assertTrue(shown!!.description!!.endsWith("original text"))
        assertEquals(APPROVAL, fixture.owner.authorization)
    }

    @Test
    fun `reconciliation cleans an unaccepted submission without inventing lifecycle events`() = runTest {
        val fixture = HookFixture(this)
        val native = FakeNativeSession()
        val session = fixture.open(native)
        val request = RequestId("run")
        native.sendFailure = EngineException(EngineFailure.Request(RequestFailureReason.Invalid, request))
        assertFailsWith<EngineException> { session.send(prompt("run")) }
        runCurrent()
        assertTrue(fixture.hooks.context(session.ref, request, null) != null)
        assertIs<FeatureAccess.Available<ReconcilesSession>>(session.features.resolve(ReconcilesSession))
            .feature.synchronize()
        runCurrent()
        assertNull(fixture.hooks.context(session.ref, request, null))
        assertEquals(listOf("Opened"), fixture.hook.events.map { it::class.simpleName })
    }

    @Test
    fun `cleanup of recovered submission survives closing its handle`() = runTest {
        val fixture = HookFixture(this)
        fixture.owner.cleanupGate = CompletableDeferred()
        val native = FakeNativeSession()
        val session = fixture.open(native)
        val request = RequestId("run")
        native.sendFailure = EngineException(EngineFailure.Request(RequestFailureReason.Invalid, request))
        assertFailsWith<EngineException> { session.send(prompt("run")) }
        assertIs<FeatureAccess.Available<ReconcilesSession>>(session.features.resolve(ReconcilesSession))
            .feature.synchronize()
        runCurrent()
        fixture.owner.cleanupStarted.await()
        session.close()
        fixture.owner.cleanupGate!!.complete(Unit)
        runCurrent()
        assertNull(fixture.hooks.context(session.ref, request, null))
    }

    @Test
    fun `a changed original approval still refuses execution after hook ask`() = runTest {
        val fixture = HookFixture(this)
        fixture.hook.verdict = ToolHookVerdict.Ask("review")
        val native = FakeNativeSession()
        fixture.open(native).send(prompt("run"))
        val result = fixture.execute(
            native,
            AgentToolPermissions {
                fixture.owner.approval = APPROVAL.copy(binding = "changed")
                true
            },
        )
        assertTrue(result.isError)
        assertEquals(0, fixture.owner.calls)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class HookFixture(private val scope: TestScope) {
    val hook = IntegrationHook()
    val hooks = scope.testSessionHooks(hook)
    val owner = HookedOwner()
    val tools = DefaultAgentTools(setOf(owner), hooks)
    private val fixture = RouteFixture(scope)
    private val policy = SessionPolicy(
        fixture.routes,
        EnabledEngines(fixture.registry, fixture.toggles, scope.backgroundScope),
        ActiveSessionRegistry(),
        fixture.context,
        tools,
        hooks,
    )
    private var nextHandle = 0

    fun open(native: FakeNativeSession, isEnabled: Boolean = true): ActiveSession {
        val route = ExecutionRoute(TestEngine, fixture.binding.id, fixture.source.info.id, fixture.source.info.revision)
        return ActiveSessionAssembler(policy, scope.backgroundScope).assemble(
            native,
            route,
            ModelId("m1"),
            TestHandleScope("handle${++nextHandle}", scope.backgroundScope),
            isEnabled,
        ).also { scope.runCurrent() }
    }

    suspend fun execute(
        native: FakeNativeSession,
        permissions: AgentToolPermissions = AgentToolPermissions { error("unexpected question") },
    ): AgentToolResult = tools.execute(
        AgentToolContext(
            native.ref,
            null,
            native.activeTurn.id,
            native.activeTurn.request,
            trust = TrustLevel.Full,
            permissions = permissions,
        ),
        "tool",
        EMPTY,
    )
}

private class IntegrationHook : SessionHook {
    override val isObserving = true
    override val isIntercepting = true
    val events = mutableListOf<SessionLifecycle>()
    val prompts = mutableListOf<String>()
    var verdict: ToolHookVerdict = ToolHookVerdict.Continue
    override suspend fun observe(event: SessionLifecycle) {
        events += event
    }
    override suspend fun beforePrompt(context: SessionHookContext, text: String): String {
        prompts += text
        return "<<<context>>>"
    }
    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict = verdict
    override suspend fun afterTool(call: HookedToolCall, result: AgentToolResult): String = "note"
}

private class HookedOwner : AgentToolContribution {
    override val isDetachedSupported = true
    var approval = APPROVAL
    var authorization: AgentToolApproval? = null
    var calls = 0
    val cleanupStarted = CompletableDeferred<Unit>()
    var cleanupGate: CompletableDeferred<Unit>? = null
    override suspend fun finishTurn(session: SessionRef, turn: TurnId) {
        cleanupStarted.complete(Unit)
        cleanupGate?.await()
    }
    override suspend fun specifications(workspace: WorkspaceRef?) = listOf(AgentToolSpec("tool", "Tool", EMPTY))
    override fun approval(spec: AgentToolSpec, arguments: JsonObject): AgentToolApproval = approval
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        calls++
        authorization = context.authorization
        return AgentToolResult("result")
    }
}

private suspend fun ActiveSession.send(request: PromptRequest): TurnId =
    assertIs<FeatureAccess.Available<SendsPrompts>>(features.resolve(SendsPrompts)).feature.send(request)
private fun prompt(text: String): PromptRequest = PromptRequest(RequestId(text), listOf(ContentPart.Text(text)))
private val APPROVAL = AgentToolApproval("tool", "Tool", "original text", "revision=1")
private val EMPTY = JsonObject(emptyMap())
