package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationAttempt
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationAttemptPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationControl
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationMessage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SubsessionCreation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.SubsessionTools
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubsessionAgentToolsTest {
    @Test
    fun `disabling starts retains recovery declarations without initializing the conversation host`() = runTest {
        val flags = ChecklistTestToggles().apply { scheduler = false }
        val tools = SubsessionAgentTools(lazy { error("Declarations must not construct the runtime") }, flags)
        assertEquals(
            setOf(SubsessionTools.STATUS, SubsessionTools.RESULT, SubsessionTools.WAIT, SubsessionTools.CANCEL),
            tools.specifications(null).map { it.name }.toSet(),
        )
        assertTrue(tools.instructions(null).contains("disabled"))
        flags.scheduler = true
        assertEquals(6, tools.specifications(null).size)
        assertTrue(tools.instructions(null).contains("full user-interactive"))
    }

    @Test
    fun `spawn needs exact authorization and derives replay identity from the trusted call`() = runTest {
        val host = ToolConversationHost()
        val tools = SubsessionAgentTools(lazyOf(host), ChecklistTestToggles())
        val arguments = arguments("title" to "Child", "message" to "Agent task")
        val spec = tools.specifications(null).single { it.name == SubsessionTools.SPAWN }
        assertTrue(tools.execute(caller, spec.name, arguments).isError)
        assertTrue(host.creations.isEmpty())
        val authorized = caller.copy(authorization = tools.approval(caller, spec, arguments))
        assertFalse(tools.execute(authorized, spec.name, arguments).isError)
        assertFalse(tools.execute(authorized, spec.name, arguments).isError)
        assertEquals(2, host.creations.size)
        assertEquals(host.creations.first(), host.creations.last())
        assertEquals(caller.subsessionRequest(spec.name), host.creations[0].request)
        assertEquals("Agent task", host.creations[0].initialMessage)
        assertTrue(tools.execute(authorized, spec.name, arguments("title" to "Changed")).isError)
        assertEquals(2, host.creations.size)
        assertNotEquals(
            caller.subsessionRequest(spec.name),
            caller.copy(callId = ToolCallId("another")).subsessionRequest(spec.name),
        )
    }

    @Test
    fun `model input cannot assign a creator and a subagent cannot invoke full conversation control`() = runTest {
        val host = ToolConversationHost()
        val tools = SubsessionAgentTools(lazyOf(host), ChecklistTestToggles())
        val spec = tools.specifications(null).single { it.name == SubsessionTools.SPAWN }
        val forged = arguments("title" to "Child", "creator" to "other")
        val authorized = caller.copy(authorization = tools.approval(caller, spec, forged))
        assertTrue(tools.execute(authorized, spec.name, forged).isError)
        val agent = caller.copy(logicalSession = root.subagent(LogicalSessionId("agent")))
        assertTrue(tools.execute(agent, SubsessionTools.STATUS, arguments("session_id" to "child")).isError)
        assertTrue(host.creations.isEmpty())
        assertEquals(0, host.reads)
    }

    @Test
    fun `send keeps agent provenance and missing call identity cannot allocate a fresh request`() = runTest {
        val host = ToolConversationHost()
        val tools = SubsessionAgentTools(lazyOf(host), ChecklistTestToggles())
        val spec = tools.specifications(null).single { it.name == SubsessionTools.SEND }
        val input = arguments("session_id" to "child", "message" to "Next task")
        val authorized = caller.copy(authorization = tools.approval(caller, spec, input))
        assertFalse(tools.execute(authorized, spec.name, input).isError)
        assertEquals(HostConversationMessage(caller.subsessionRequest(spec.name), "Next task"), host.messages.single())
        val unbound = caller.copy(callId = null)
        assertTrue(
            tools.execute(unbound.copy(authorization = tools.approval(unbound, spec, input)), spec.name, input).isError,
        )
        assertEquals(1, host.messages.size)
    }

    @Test
    fun `recovery and exact cancellation remain available while starts are disabled`() = runTest {
        val host = ToolConversationHost()
        val flags = ChecklistTestToggles().apply { scheduler = false }
        val tools = SubsessionAgentTools(lazyOf(host), flags)
        val input = arguments("session_id" to "child", "request_id" to "old")
        assertFalse(tools.execute(caller, SubsessionTools.STATUS, arguments("session_id" to "child")).isError)
        assertFalse(tools.execute(caller, SubsessionTools.RESULT, input).isError)
        val cancel = tools.specifications(null).single { it.name == SubsessionTools.CANCEL }
        val authorized = caller.copy(authorization = tools.approval(caller, cancel, input))
        assertFalse(tools.execute(authorized, cancel.name, input).isError)
        assertEquals(listOf(RequestId("old")), host.cancelled)
        assertTrue(tools.execute(caller, SubsessionTools.SPAWN, arguments("title" to "Child")).isError)
    }

    @Test
    fun `wait timeout and caller cancellation never stop or restart the child`() = runTest {
        val host = ToolConversationHost()
        val tools = SubsessionAgentTools(lazyOf(host), ChecklistTestToggles())
        val input = buildJsonObject {
            put("session_id", "child")
            put("request_id", "pending")
            put("timeout_ms", 750)
        }
        val response = tools.execute(caller, SubsessionTools.WAIT, input)
        assertFalse(response.isError)
        assertTrue(response.text.contains("\"isTimedOut\":true"))
        val wait = async { tools.execute(caller, SubsessionTools.WAIT, input) }
        runCurrent()
        assertFalse(wait.isCompleted)
        wait.cancelAndJoin()
        assertTrue(host.cancelled.isEmpty())
        assertTrue(host.creations.isEmpty())
        assertTrue(host.messages.isEmpty())
    }

    @Test
    fun `wait returns only evidence for the requested attempt`() = runTest {
        val host = ToolConversationHost().apply {
            completed = HostConversationResult(RequestId("done"), TurnOutcome.Completed, "Result")
        }
        val tools = SubsessionAgentTools(lazyOf(host), ChecklistTestToggles())
        val response = tools.execute(
            caller,
            SubsessionTools.WAIT,
            arguments("session_id" to "child", "request_id" to "done"),
        )
        assertFalse(response.isError)
        assertTrue(response.text.contains("Result"))
        assertFalse(response.text.contains("isTimedOut"))
        assertEquals(listOf(RequestId("done")), host.results)
    }
}

private val root = LogicalSessionContext(LogicalSessionId("root"), SessionKind.Session)
private val caller = AgentToolContext(
    SessionRef(EngineId("test"), SessionSourceId("local"), "native"),
    null,
    TurnId("turn"),
    RequestId("parent"),
    callId = ToolCallId("call"),
    logicalSession = root,
)

private fun arguments(vararg values: Pair<String, String>): JsonObject = buildJsonObject {
    values.forEach { (name, value) -> put(name, value) }
}

private class ToolConversationHost : HostConversationControl {
    val creations = mutableListOf<SubsessionCreation>()
    val messages = mutableListOf<HostConversationMessage>()
    val cancelled = mutableListOf<RequestId>()
    val results = mutableListOf<RequestId>()
    var reads = 0
    var completed: HostConversationResult? = null
    private val snapshot = HostConversationSnapshot(
        root.subsession(LogicalSessionId("child")),
        "Child",
        attempt = HostConversationAttempt(RequestId("pending"), HostConversationAttemptPhase.Unknown),
    )
    override suspend fun createSubsession(
        caller: AgentToolContext,
        creation: SubsessionCreation,
    ): HostConversationSnapshot {
        creations += creation
        return snapshot
    }
    override suspend fun send(
        caller: AgentToolContext,
        child: LogicalSessionId,
        message: HostConversationMessage,
    ): HostConversationSnapshot {
        messages += message
        return snapshot
    }
    override suspend fun state(caller: AgentToolContext, child: LogicalSessionId): HostConversationSnapshot {
        reads++
        return snapshot
    }
    override suspend fun result(
        caller: AgentToolContext,
        child: LogicalSessionId,
        request: RequestId,
    ): HostConversationResult? {
        results += request
        return completed?.takeIf { it.request == request }
    }
    override suspend fun cancel(
        caller: AgentToolContext,
        child: LogicalSessionId,
        request: RequestId,
    ): HostConversationCancellation {
        cancelled += request
        return HostConversationCancellation.Unconfirmed(request)
    }
}
