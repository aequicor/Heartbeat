package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_REQUEST_PREFIX
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ContextHandoffHookTest {
    private val hook = ContextHandoffHook { true }

    @Test
    fun `tool calls of a handoff summary turn are refused, native ones too`() = runTest {
        assertIs<ToolHookVerdict.Deny>(hook.beforeTool(call(RequestId("${HANDOFF_REQUEST_PREFIX}1"))))
        assertIs<ToolHookVerdict.Deny>(hook.beforeTool(call(RequestId("${HANDOFF_REQUEST_PREFIX}1"), isNative = true)))
    }

    @Test
    fun `other turns and calls without a request continue`() = runTest {
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(call(RequestId("compact-1"))))
        // A session engine transfer uses its own handoff- request ids.
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(call(RequestId("handoff-0a1b"))))
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(call(null)))
    }

    @Test
    fun `the hook intercepts only while enabled`() {
        assertFalse(ContextHandoffHook { false }.isIntercepting)
    }

    private fun call(request: RequestId?, isNative: Boolean = false) = HookedToolCall(
        SessionHookContext(Session, null, request, null, SessionOwner("owner")),
        "shell",
        AgentToolAction.Command,
        JsonObject(emptyMap()),
        isNative,
    )

    private companion object {
        val Session = SessionRef(EngineId("codex"), SessionSourceId("s"), "thread")
    }
}
