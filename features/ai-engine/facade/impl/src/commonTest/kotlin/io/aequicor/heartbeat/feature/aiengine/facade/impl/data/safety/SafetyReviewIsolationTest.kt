package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyReviewIsolationTest {
    @Test
    fun `a review denies hosted and native calls even without its request prefix`() = runTest {
        val policy = AgentSafetyToolPolicy()
        val hook = AgentSafetyHook(policy)
        val review = SessionRef(EngineId("engine"), SessionSourceId("source"), "review")
        val user = review.copy(nativeId = "user")
        policy.restrict(review, listOf("shell", "read"))
        assertTrue(hook.isIntercepting)
        assertEquals(
            mapOf("shell" to ToolSwitch.Off, "read" to ToolSwitch.Off),
            policy.policy(ToolPolicyScope(session = review))?.native,
        )
        assertNull(policy.policy(ToolPolicyScope(session = user)))
        for (isNative in listOf(false, true)) {
            assertIs<ToolHookVerdict.Deny>(hook.beforeTool(call(review, isNative)))
            assertEquals(ToolHookVerdict.Continue, hook.beforeTool(call(user, isNative)))
        }
        val nested = call(user, false)
        val context = nested.context.copy(request = RequestId(REVIEW_REQUEST_PREFIX + "nested"))
        assertIs<ToolHookVerdict.Deny>(hook.beforeTool(nested.copy(context = context)))
        policy.release(review)
        assertFalse(hook.isIntercepting)
        assertNull(policy.policy(ToolPolicyScope(session = review)))
    }

    @Test
    fun `an isolated prompt request is denied tools after its session was released`() = runTest {
        val hook = AgentSafetyHook(AgentSafetyToolPolicy())
        val user = SessionRef(EngineId("engine"), SessionSourceId("source"), "user")
        val nested = call(user, true)
        val context = nested.context.copy(request = RequestId(ISOLATED_REQUEST_PREFIX + "router"))
        assertIs<ToolHookVerdict.Deny>(hook.beforeTool(nested.copy(context = context)))
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(nested))
    }

    private fun call(session: SessionRef, isNative: Boolean) = HookedToolCall(
        SessionHookContext(session, null, null, null, SessionOwner("review")),
        "harness_create",
        AgentToolAction.Read,
        JsonObject(emptyMap()),
        isNative = isNative,
    )
}
