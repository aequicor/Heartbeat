package io.aequicor.heartbeat.feature.browser.impl.data

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
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

class ExternalBrowserSafetyTest {
    private val target = EngineTarget(EngineId("engine"), EngineBindingId("provider"), ModelId("model"))
    private val context = AgentToolContext(
        SessionRef(target.engine, SessionSourceId("source"), "chat"),
        WorkspaceRef("project"),
        TurnId("turn"),
        request = RequestId("request"),
        callId = ToolCallId("call"),
        target = target,
    )
    private val original = AgentToolApproval("ex_control_type", "Insert draft", binding = "original")
    private val arguments = buildJsonObject {
        put("tabId", "7")
        put("text", "Draft query")
    }
    private val snapshot = buildJsonObject {
        put("url", "https://example.com/")
        put("focused", "private form contents")
    }
    private val reviewer = Reviewer()
    private val safety = ExternalBrowserSafety(reviewer)

    @Test
    fun `a cached safe assessment permits exactly one consumption and is never reviewed at execution`() = runTest {
        val request = request()
        val caller = context.copy(authorization = request.approval)
        assertFalse(safety.consume(caller, request))
        assertIs<AgentToolAutomaticAuthorization.Allowed>(safety.authorize(context, request))
        assertIs<AgentToolAutomaticAuthorization.Allowed>(safety.authorize(context, request))
        assertFalse(safety.consume(context, request))
        assertTrue(safety.consume(caller, request))
        assertFalse(safety.consume(caller, request))
        assertEquals(1, reviewer.calls)
        assertEquals(target, reviewer.target)
        assertEquals(request.change, reviewer.change)
        assertTrue("private form contents" in request.change)
        assertFalse("private form contents" in request.toString())
    }

    @Test
    fun `authorization cannot cross caller workspace turn request call or model identity`() = runTest {
        val reviewed = request()
        safety.authorize(context, reviewed)
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
            assertNotEquals(reviewed.approval.binding, changed.approval.binding)
            assertFalse(safety.consume(caller.copy(authorization = reviewed.approval), changed))
        }
        assertTrue(safety.consume(context.copy(authorization = reviewed.approval), reviewed))
        assertEquals(1, reviewer.calls)
    }

    @Test
    fun `changed raw arguments effective arguments page and original approval all invalidate authorization`() =
        runTest {
            val reviewed = request()
            safety.authorize(context, reviewed)
            val changes = listOf(
                request(arguments = buildJsonObject { put("text", "Send") }),
                request(effective = buildJsonObject { put("approvedTabId", "99") }),
                request(snapshot = buildJsonObject { put("url", "https://example.com/checkout") }),
                request(original = original.copy(name = "ex_control_key")),
                request(original = original.copy(title = "Another action")),
                request(original = original.copy(description = "Another page")),
                request(original = original.copy(binding = "another active tab")),
            )
            for (changed in changes) {
                assertNotEquals(reviewed.approval.binding, changed.approval.binding)
                assertFalse(safety.consume(context.copy(authorization = reviewed.approval), changed))
            }
            assertTrue(safety.consume(context.copy(authorization = reviewed.approval), reviewed))
            assertEquals(1, reviewer.calls)
        }

    @Test
    fun `risky uncertain and malformed safe verdicts cannot be consumed as authorization`() = runTest {
        for (verdict in listOf("risky", "uncertain", "safe")) {
            val assessor = Reviewer().also {
                it.result = AgentSafetyReview(
                    verdict,
                    if (verdict == "safe") "" else "Unsafe effect",
                )
            }
            val gate = ExternalBrowserSafety(assessor)
            val request = request()
            assertIs<AgentToolAutomaticAuthorization.Denied>(gate.authorize(context, request))
            assertFalse(gate.consume(context.copy(authorization = request.approval), request))
            assertEquals(1, assessor.calls)
        }
    }

    @Test
    fun `review exceptions are redacted and missing targets never dispatch a review`() = runTest {
        reviewer.failure = IllegalStateException("private backend detail")
        val request = request()
        val denied = assertIs<AgentToolAutomaticAuthorization.Denied>(safety.authorize(context, request))
        assertFalse("private backend detail" in denied.reason)
        assertFalse(safety.consume(context.copy(authorization = request.approval), request))
        val missing = context.copy(target = null)
        assertIs<AgentToolAutomaticAuthorization.Denied>(safety.authorize(missing, request(missing)))
        assertEquals(1, reviewer.calls)
    }

    @Test
    fun `cancellation propagates and leaves no cached decision`() = runTest {
        val request = request()
        reviewer.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { safety.authorize(context, request) }
        assertFalse(safety.consume(context.copy(authorization = request.approval), request))
        reviewer.failure = null
        assertIs<AgentToolAutomaticAuthorization.Allowed>(safety.authorize(context, request))
        assertTrue(safety.consume(context.copy(authorization = request.approval), request))
        assertEquals(2, reviewer.calls)
    }

    @Test
    fun `reviews of different proposals run in parallel`() = runTest {
        val gate = CompletableDeferred<Unit>().also { reviewer.gate = it }
        val first = request()
        val second = request(arguments = buildJsonObject { put("text", "Other draft") })
        val pending = listOf(async { safety.authorize(context, first) }, async { safety.authorize(context, second) })
        runCurrent()
        assertEquals(2, reviewer.calls)
        gate.complete(Unit)
        pending.forEach { assertIs<AgentToolAutomaticAuthorization.Allowed>(it.await()) }
        assertTrue(safety.consume(context.copy(authorization = first.approval), first))
    }

    private fun request(
        caller: AgentToolContext = context,
        original: AgentToolApproval = this.original,
        arguments: JsonObject = this.arguments,
        effective: JsonObject = arguments,
        snapshot: JsonObject = this.snapshot,
    ): ExternalBrowserSafetyRequest = externalBrowserSafetyRequest(caller, original, arguments, effective, snapshot)

    private class Reviewer : AgentSafetyReviewer {
        var calls = 0
        var result = AgentSafetyReview("safe", "Bounded local action")
        var failure: Exception? = null
        var target: EngineTarget? = null
        var change: String? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun review(target: EngineTarget, policy: String, change: String): AgentSafetyReview {
            calls++
            this.target = target
            this.change = change
            gate?.await()
            failure?.let { throw it }
            return result
        }
    }
}
