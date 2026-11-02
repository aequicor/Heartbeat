package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRelay
import io.aequicor.heartbeat.feature.aiengine.facade.api.RelaysPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BorrowedPermissionRelayTest {
    @Test
    fun `borrowed relay awaits exact native acknowledgement without opening a handle`() = runTest {
        val f = Fixture()
        f.borrowed.respond(f.relay, f.decision)
        assertEquals(1, f.responses)
        assertEquals(0, f.native.closes)
    }

    @Test
    fun `same permission id acknowledged by another request does not confirm delivery`() = runTest {
        val f = Fixture()
        f.acknowledged = f.turn.copy(request = RequestId("foreign"))
        assertFailsWith<IllegalStateException> { f.borrowed.respond(f.relay, f.decision) }
        assertEquals(1, f.responses)
    }

    @Test
    fun `missing or ambiguous live handles refuse without trying another session`() = runTest {
        val f = Fixture()
        f.handles = emptyList()
        assertFailsWith<IllegalStateException> { f.borrowed.respond(f.relay, f.decision) }
        f.handles = listOf(f.handle, f.handle)
        assertFailsWith<IllegalStateException> { f.borrowed.respond(f.relay, f.decision) }
        assertEquals(0, f.responses)
    }

    private class Fixture {
        val turn = Turn(TurnId("turn"), RequestId("work"), TestTarget)
        val request = permission("ask", turn.id).let {
            it.copy(options = it.options.map { option -> option.copy(isOneTime = true) })
        }
        val native = FakeNativeSession(
            initial = ActiveSessionState.AwaitingUserAction(turn, listOf(request)),
        )
        val owner = LogicalSessionContext(LogicalSessionId("owner"), SessionKind.Session)
        val binding = SessionExecutionBinding(
            SessionExecutionInstance("host", "one"),
            owner.subagent(LogicalSessionId("child")),
        )
        val relay = PermissionRelay(binding, owner.id, native.ref, TestTarget, turn.request!!, request, "nonce", 1)
        val decision = PermissionDecision(turn.id, request.id, request.options.first().id)
        var acknowledged = turn
        var responses = 0
        private val responder = object : RelaysPermissions {
            override suspend fun respond(relay: PermissionRelay, decision: PermissionDecision) {
                responses++
                native.native.value = ActiveSessionState.Running(
                    acknowledged.copy(resolvedPermissions = setOf(decision.request)),
                )
            }
        }
        val handle = object : ActiveSession by native {
            override val features = FeatureTable(mapOf(RelaysPermissions.id to available(responder)))
        }
        var handles: List<ActiveSession> = listOf(handle)
        val borrowed = BorrowedPermissionRelay(native.ref) { handles }
    }
}
