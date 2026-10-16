package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutomaticNativeSafetyTest {
    @Test
    fun `safe bash true never asks even when adapter trust coverage returns false`() = runTest {
        for (trust in TrustLevel.entries) {
            val f = NativeSafetyFixture()
            assertEquals(NativeVerdict.Allow, f.tools.authorizeNative(Context.copy(trust = trust), Call))
            assertEquals(1, f.reviews.size)
            assertEquals(NativePreparation.Allow, f.tools.prepareNative(Context.copy(trust = trust), Call))
        }
    }

    @Test
    fun `host bound mode and target override adapter values before reviewing full input`() = runTest {
        val f = NativeSafetyFixture()
        f.tools.bindTurn(Session, Request, TurnId("facade"), Target, AgentToolDecisionMode.AutomaticSafety)
        val adapter = Context.copy(
            target = Target.copy(model = ModelId("different")),
            decisionMode = AgentToolDecisionMode.Interactive,
        )
        assertEquals(NativeVerdict.Allow, f.tools.authorizeNative(adapter, Call))
        val snapshot = Json.parseToJsonElement(f.reviews.single()).jsonObject
        assertEquals(Target, f.target)
        assertEquals(Json.encodeToJsonElement(Session), snapshot["session"])
        assertEquals(Json.encodeToJsonElement(Target), snapshot["target"])
        assertEquals(JsonPrimitive("facade"), snapshot["turn"])
        assertEquals(JsonPrimitive("request"), snapshot["request"])
        assertEquals(JsonPrimitive("call"), snapshot["callId"])
        assertEquals(JsonPrimitive("workspace"), snapshot["workspace"])
        assertEquals(JsonPrimitive("/project"), snapshot["workingDirectory"])
        assertEquals(JsonPrimitive("true"), snapshot["command"])
        assertEquals(Call.arguments, snapshot["arguments"])
        assertFailsWith<IllegalStateException> {
            f.tools.bindTurn(Session, Request, TurnId("facade"), Target, AgentToolDecisionMode.Interactive)
        }
    }

    @Test
    fun `a host bound review model reviews the turn instead of its own model`() = runTest {
        val f = NativeSafetyFixture()
        val parent = Target.copy(model = ModelId("parent"))
        f.tools.bindTurn(Session, Request, TurnId("facade"), Target, AgentToolDecisionMode.AutomaticSafety, parent)
        val adapter = Context.copy(reviewTarget = Target.copy(model = ModelId("forged")))
        assertEquals(NativeVerdict.Allow, f.tools.authorizeNative(adapter, Call))
        assertEquals(parent, f.target)
        val snapshot = Json.parseToJsonElement(f.reviews.single()).jsonObject
        assertEquals(Json.encodeToJsonElement(Target), snapshot["target"])
        assertEquals(Json.encodeToJsonElement(parent), snapshot["reviewTarget"])
    }

    @Test
    fun `without a review model the turn reviews on its own model`() = runTest {
        val f = NativeSafetyFixture()
        assertEquals(NativeVerdict.Allow, f.tools.authorizeNative(Context, Call))
        assertEquals(Target, f.target)
        assertTrue("reviewTarget" !in Json.parseToJsonElement(f.reviews.single()).jsonObject)
    }

    @Test
    fun `risk uncertainty malformed verdict and reviewer failure never use full trust or permissions`() = runTest {
        for (verdict in listOf("risk", "uncertain", "SAFE", "")) {
            val f = NativeSafetyFixture().apply { assess = { AgentSafetyReview(verdict, "Refused") } }
            assertIs<NativeVerdict.Deny>(f.tools.authorizeNative(Context.copy(trust = TrustLevel.Full), Call))
        }
        val failing = NativeSafetyFixture().apply { assess = { error("private provider secret") } }
        val result = assertIs<NativeVerdict.Deny>(failing.tools.authorizeNative(Context, Call))
        assertTrue("private" !in result.reason)
    }

    @Test
    fun `unknown or off tools and hook ask or deny stop before independent review`() = runTest {
        val unknown = NativeSafetyFixture()
        assertIs<NativeVerdict.Deny>(unknown.tools.authorizeNative(Context, Call.copy(name = "unknown")))
        assertTrue(unknown.reviews.isEmpty())
        for (hook in listOf(ToolHookVerdict.Ask("ask"), ToolHookVerdict.Deny("deny"))) {
            val f = NativeSafetyFixture().apply { verdict = hook }
            assertIs<NativeVerdict.Deny>(f.tools.authorizeNative(Context, Call))
            assertTrue(f.reviews.isEmpty())
        }
        val off = NativeSafetyFixture().apply { policy = Enabled.copy(nativeOff = setOf("bash")) }
        assertIs<NativeVerdict.Deny>(off.tools.authorizeNative(Context, Call))
        assertTrue(off.reviews.isEmpty())
    }

    @Test
    fun `changed policy generation or unavailable policy after a safe result refuses execution`() = runTest {
        for (replacement in listOf(null, Enabled.copy(generation = 2), Enabled.copy(nativeOff = setOf("bash")))) {
            val f = NativeSafetyFixture()
            f.assess = {
                f.policy = replacement
                Safe
            }
            assertIs<NativeVerdict.Deny>(f.tools.authorizeNative(Context, Call))
        }
    }

    @Test
    fun `a hook becoming ask or deny during review cannot fall back to permission`() = runTest {
        for (hook in listOf(ToolHookVerdict.Ask("ask"), ToolHookVerdict.Deny("deny"))) {
            val f = NativeSafetyFixture()
            f.assess = {
                f.verdict = hook
                Safe
            }
            assertIs<NativeVerdict.Deny>(f.tools.authorizeNative(Context, Call))
        }
    }

    @Test
    fun `mutating input during suspended review cannot reuse the safe snapshot`() = runTest {
        val input = mutableMapOf("command" to JsonPrimitive("true"))
        val f = NativeSafetyFixture()
        f.assess = {
            input["command"] = JsonPrimitive("changed")
            Safe
        }
        assertIs<NativeVerdict.Deny>(f.tools.authorizeNative(Context, Call.copy(arguments = JsonObject(input))))
        assertTrue("changed" !in f.reviews.single())
    }

    @Test
    fun `missing identity incomplete input and host termination are denied without reviewing`() = runTest {
        val f = NativeSafetyFixture()
        val incomplete = listOf(Context.copy(target = null), Context.copy(request = null), Context.copy(callId = null))
        for (context in incomplete) {
            assertIs<NativeVerdict.Deny>(f.tools.authorizeNative(context, Call))
        }
        for (call in listOf(
            Call.copy(arguments = null),
            Call.copy(workingDirectory = null),
            Call.copy(command = ""),
            Call.copy(automaticDenial = "Cannot terminate Heartbeat"),
            Call.copy(arguments = JsonObject(mapOf("content" to JsonPrimitive("x".repeat(70_000))))),
        )) {
            assertIs<NativeVerdict.Deny>(f.tools.authorizeNative(Context, call))
        }
        assertTrue(f.reviews.isEmpty())
        val unavailable = DefaultAgentTools(emptySet(), policies = ToolPolicyResolver { _, _, _ -> Enabled })
        assertIs<NativeVerdict.Deny>(unavailable.authorizeNative(Context, Call))
    }

    @Test
    fun `detached edits and classifier rejected outside paths refuse before reviewer`() = runTest {
        val f = NativeSafetyFixture()
        val edit = Call.copy(action = AgentToolAction.Edit, paths = listOf("/project/file"), command = null)
        assertIs<NativeVerdict.Deny>(f.tools.authorizeNative(Context.copy(workspace = null), edit))
        assertIs<NativeVerdict.Deny>(
            f.tools.authorizeNative(
                Context,
                edit.copy(automaticDenial = "The path resolves outside the trusted workspace"),
            ),
        )
        assertTrue(f.reviews.isEmpty())
    }

    @Test
    fun `finishing or cancelling the native turn revokes a suspended review`() = runTest {
        for (isNativeCancellation in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val lifetime = Job()
            val f = NativeSafetyFixture().apply {
                assess = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val call = async { f.tools.authorizeNative(Context.copy(lifetime = lifetime), Call) }
            entered.await()
            if (isNativeCancellation) lifetime.cancel() else f.tools.finishTurn(Session, Context.turn)
            assertFailsWith<CancellationException> { call.await() }
        }
    }

    @Test
    fun `restored automatic mode remains noninteractive without an in memory turn binding`() = runTest {
        val f = NativeSafetyFixture()
        val stored = Turn(Context.turn, Request, Target, decisionMode = AgentToolDecisionMode.AutomaticSafety)
        val restored = Json.decodeFromString<Turn>(Json.encodeToString(stored))
        val context = Context.copy(decisionMode = restored.decisionMode)
        assertEquals(NativeVerdict.Allow, f.tools.authorizeNative(context, Call))
        f.assess = { AgentSafetyReview("uncertain", "Unknown") }
        assertIs<NativeVerdict.Deny>(f.tools.authorizeNative(context, Call))
    }
}

private class NativeSafetyFixture : SessionHooks {
    var policy: ResolvedToolPolicy? = Enabled
    var verdict: ToolHookVerdict = ToolHookVerdict.Continue
    var assess: suspend () -> AgentSafetyReview = { Safe }
    var target: EngineTarget? = null
    val reviews = mutableListOf<String>()
    val tools = DefaultAgentTools(
        emptySet(),
        this,
        ToolPolicyResolver { _, _, _ -> policy },
        safety = lazyOf(
            AgentSafetyReviewer { reviewedTarget, _, change ->
                target = reviewedTarget
                reviews += change
                assess()
            },
        ),
    )

    override fun context(session: SessionRef, request: RequestId?, turn: TurnId?) =
        SessionHookContext(session, Context.workspace, request, turn, SessionOwner("test"))

    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict = verdict
}

private val Target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("model"))
private val Session = SessionRef(Target.engine, SessionSourceId("local"), "caller")
private val Request = RequestId("request")
private val Context = AgentToolContext(
    Session,
    WorkspaceRef("workspace"),
    TurnId("native"),
    Request,
    permissions = AgentToolPermissions { throw AssertionError("Automatic safety cannot open a permission dialog") },
    callId = ToolCallId("call"),
    target = Target,
    decisionMode = AgentToolDecisionMode.AutomaticSafety,
)
private val Call = NativeToolCall(
    "bash",
    AgentToolAction.Command,
    command = "true",
    covered = { throw AssertionError("Automatic safety cannot skip review through trust coverage") },
    arguments = JsonObject(mapOf("command" to JsonPrimitive("true"), "timeout" to JsonPrimitive(30))),
    workingDirectory = "/project",
)
private val Enabled = ResolvedToolPolicy(nativeOn = setOf("bash"), generation = 1)
private val Safe = AgentSafetyReview("safe", "Bounded local operation")
