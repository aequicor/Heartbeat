package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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

    @Test
    fun `the memo binds every trusted caller and model identity without reviewing at execution`() = runTest {
        val originalRequest = request()
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
        }
    }

    @Test
    fun `arguments source activation and original proposal each change the authorization binding`() = runTest {
        val originalRequest = request()
        val changed = listOf(
            request(arguments = buildJsonObject { put("method", "delete") }),
            request(snapshot = buildJsonObject { put("source", "replacement source") }),
            request(binding = "replacement callback"),
            uiToolOperation(context, original.copy(binding = "new revision"), arguments, source, "callback"),
        )
        for (proposal in changed) {
            assertNotEquals(originalRequest.approval.binding, proposal.approval.binding)
        }
    }

    @Test
    fun `a library revision change invalidates identical surface snapshots`() = runTest {
        val reviewed = request(libraryRevision = 7)
        val recreated = request(libraryRevision = 9)
        assertEquals(7L, reviewed.libraryRevision)
        assertNotEquals(reviewed.approval.binding, recreated.approval.binding)
    }

    private fun request(
        caller: AgentToolContext = context,
        arguments: JsonObject = this.arguments,
        snapshot: JsonObject = source,
        binding: String = "callback",
        libraryRevision: Long? = null,
    ): UiToolOperation = uiToolOperation(caller, original, arguments, snapshot, binding, libraryRevision)
}
