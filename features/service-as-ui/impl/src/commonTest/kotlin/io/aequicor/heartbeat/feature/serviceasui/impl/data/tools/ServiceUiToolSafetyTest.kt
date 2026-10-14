package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServiceUiToolSafetyTest {
    private val target = EngineTarget(EngineId("engine"), EngineBindingId("provider"), ModelId("model"))
    private val context = AgentToolContext(
        SessionRef(target.engine, SessionSourceId("source"), "chat"),
        WorkspaceRef("project"),
        TurnId("turn"),
        request = RequestId("request"),
        callId = ToolCallId("call"),
        target = target,
    )
    private val original = AgentToolApproval("ui_call", "Call snapshot", binding = "original")
    private val arguments = buildJsonObject { put("method", "snapshot") }
    private val source = buildJsonObject { put("source", "private source") }
    private val reviewer = Reviewer()
    private val safety = ServiceUiToolSafety(reviewer)

    @Test
    fun `only a cached safe assessment authorizes execution and exact repeats reuse it`() = runTest {
        val request = request()
        val authorized = context.copy(authorization = request.approval)
        assertFalse(safety.isAuthorized(authorized, request))
        assertIs<AgentToolAutomaticAuthorization.Allowed>(safety.authorize(context, request))
        assertTrue(safety.isAuthorized(authorized, request))
        assertIs<AgentToolAutomaticAuthorization.Allowed>(safety.authorize(context, request))
        assertEquals(1, reviewer.calls)
        assertEquals(target, reviewer.target)
        assertEquals(request.change, reviewer.change)
        assertTrue("private source" in request.change)
        assertFalse("private source" in request.toString())
        assertFalse(safety.isAuthorized(context, request))
    }

    @Test
    fun `the memo binds every trusted caller and model identity without reviewing at execution`() = runTest {
        val originalRequest = request()
        safety.authorize(context, originalRequest)
        val callers = listOf(
            context.copy(session = context.session.copy(nativeId = "other")),
            context.copy(session = context.session.copy(source = SessionSourceId("other"))),
            context.copy(workspace = WorkspaceRef("other")),
            context.copy(turn = TurnId("other")),
            context.copy(request = RequestId("other")),
            context.copy(callId = ToolCallId("other")),
            context.copy(target = target.copy(model = ModelId("other"))),
            context.copy(target = target.copy(binding = EngineBindingId("other"))),
            context.copy(target = target.copy(engine = EngineId("other"))),
        )
        for (caller in callers) {
            val changed = request(caller)
            assertNotEquals(originalRequest.approval.binding, changed.approval.binding)
            assertFalse(safety.isAuthorized(caller.copy(authorization = originalRequest.approval), changed))
        }
        assertEquals(1, reviewer.calls)
    }

    @Test
    fun `arguments source activation and original proposal each change the authorization binding`() = runTest {
        val originalRequest = request()
        safety.authorize(context, originalRequest)
        val changed = listOf(
            request(arguments = buildJsonObject { put("method", "delete") }),
            request(snapshot = buildJsonObject { put("source", "replacement source") }),
            request(binding = "replacement callback"),
            uiToolSafetyRequest(context, original.copy(binding = "new revision"), arguments, source, "callback"),
        )
        for (proposal in changed) {
            assertNotEquals(originalRequest.approval.binding, proposal.approval.binding)
            assertFalse(safety.isAuthorized(context.copy(authorization = originalRequest.approval), proposal))
        }
        assertEquals(1, reviewer.calls)
    }

    @Test
    fun `risk uncertainty malformed safe and failed reviews all refuse execution`() = runTest {
        val verdicts = listOf(
            AgentSafetyReview("risky", "Unsafe effect"),
            AgentSafetyReview("uncertain", "Unknown effect"),
            AgentSafetyReview("safe", ""),
        )
        for (verdict in verdicts) {
            val assessor = Reviewer().also { it.result = verdict }
            val gate = ServiceUiToolSafety(assessor)
            val request = request()
            assertIs<AgentToolAutomaticAuthorization.Denied>(gate.authorize(context, request))
            assertFalse(gate.isAuthorized(context.copy(authorization = request.approval), request))
        }
        reviewer.failure = IllegalStateException("private backend detail")
        val denied = assertIs<AgentToolAutomaticAuthorization.Denied>(safety.authorize(context, request()))
        assertFalse("private backend detail" in denied.reason)
        val missing = context.copy(target = null)
        assertIs<AgentToolAutomaticAuthorization.Denied>(safety.authorize(missing, request(missing)))
        assertEquals(1, reviewer.calls, "Missing target must not dispatch a review")
    }

    @Test
    fun `cancellation is not cached as a decision and a later attempt can be reviewed`() = runTest {
        val request = request()
        reviewer.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { safety.authorize(context, request) }
        assertFalse(safety.isAuthorized(context.copy(authorization = request.approval), request))
        reviewer.failure = null
        assertIs<AgentToolAutomaticAuthorization.Allowed>(safety.authorize(context, request))
        assertEquals(2, reviewer.calls)
    }

    @Test
    fun `a library revision change invalidates identical surface snapshots`() = runTest {
        val reviewed = request(libraryRevision = 7)
        val recreated = request(libraryRevision = 9)
        assertEquals(7L, reviewed.libraryRevision)
        assertNotEquals(reviewed.approval.binding, recreated.approval.binding)
        safety.authorize(context, reviewed)
        assertFalse(safety.isAuthorized(context.copy(authorization = reviewed.approval), recreated))
        assertEquals(1, reviewer.calls)
    }

    private fun request(
        caller: AgentToolContext = context,
        arguments: JsonObject = this.arguments,
        snapshot: JsonObject = source,
        binding: String = "callback",
        libraryRevision: Long? = null,
    ): UiToolSafetyRequest = uiToolSafetyRequest(caller, original, arguments, snapshot, binding, libraryRevision)

    private class Reviewer : AgentSafetyReviewer {
        var calls = 0
        var result = AgentSafetyReview("safe", "Bounded local action")
        var failure: Exception? = null
        var target: EngineTarget? = null
        var change: String? = null
        override suspend fun review(target: EngineTarget, policy: String, change: String): AgentSafetyReview {
            calls++
            this.target = target
            this.change = change
            failure?.let { throw it }
            return result
        }
    }
}
