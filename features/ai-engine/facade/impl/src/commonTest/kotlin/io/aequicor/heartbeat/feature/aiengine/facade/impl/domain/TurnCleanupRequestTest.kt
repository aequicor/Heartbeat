package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TurnCleanupRequestTest {
    @Test
    fun `legacy finish resolves exact turn request before hook release rather than newest session request`() = runTest {
        var isReleased = false
        val requests = mutableListOf<RequestId?>()
        val owner = CleanupOwner { request ->
            assertFalse(isReleased)
            requests += request
        }
        val hooks = object : SessionHooks {
            override fun releaseTurn(session: SessionRef, turn: TurnId, isRejected: Boolean) {
                isReleased = true
            }
        }
        val tools = DefaultAgentTools(setOf(owner), hooks)
        val first = RequestId("first")
        tools.bindTurn(SESSION, first, TurnId("first_turn"))
        tools.bindTurn(SESSION, RequestId("newest"), TurnId("newest_turn"))
        tools.finishTurn(SESSION, TurnId("first_turn"))
        assertEquals(listOf<RequestId?>(first), requests)
    }

    @Test
    fun `unbound external turn remains unknown unless trusted caller supplies its request`() = runTest {
        val requests = mutableListOf<RequestId?>()
        val tools = DefaultAgentTools(setOf(CleanupOwner { requests += it }))
        tools.bindTurn(SESSION, RequestId("unrelated"), TurnId("other_turn"))
        tools.finishTurn(SESSION, TurnId("external"))
        tools.finishTurn(SESSION, TurnId("external_exact"), RequestId("external_request"))
        assertEquals(listOf(null, RequestId("external_request")), requests)
    }

    @Test
    fun `explicit request cannot contradict canonical turn binding`() = runTest {
        val requests = mutableListOf<RequestId?>()
        val tools = DefaultAgentTools(setOf(CleanupOwner { requests += it }))
        tools.bindTurn(SESSION, RequestId("accepted"), TurnId("turn"))
        assertFailsWith<IllegalStateException> {
            tools.finishTurn(SESSION, TurnId("turn"), RequestId("different"))
        }
        assertEquals(emptyList(), requests)
        tools.finishTurn(SESSION, TurnId("turn"), RequestId("accepted"))
        assertEquals(listOf<RequestId?>(RequestId("accepted")), requests)
    }

    @Test
    fun `new contribution overload delegates to existing legacy cleanup`() = runTest {
        var finished: TurnId? = null
        val owner = object : AgentToolContribution {
            override val group = "legacy"
            override val title = "Legacy"
            override suspend fun specifications(workspace: WorkspaceRef?) = emptyList<AgentToolSpec>()
            override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject) =
                AgentToolResult("unused")
            override suspend fun finishTurn(session: SessionRef, turn: TurnId) {
                finished = turn
            }
        }
        DefaultAgentTools(setOf(owner)).finishTurn(SESSION, TurnId("legacy"), RequestId("request"))
        assertEquals(TurnId("legacy"), finished)
    }
}

private class CleanupOwner(private val finished: (RequestId?) -> Unit) : AgentToolContribution {
    override val group = "cleanup"
    override val title = "Cleanup"
    override suspend fun specifications(workspace: WorkspaceRef?) = emptyList<AgentToolSpec>()
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject) =
        AgentToolResult("unused")
    override suspend fun finishTurn(session: SessionRef, turn: TurnId, request: RequestId?) = finished(request)
}

private val SESSION = SessionRef(EngineId("engine"), SessionSourceId("source"), "session")
