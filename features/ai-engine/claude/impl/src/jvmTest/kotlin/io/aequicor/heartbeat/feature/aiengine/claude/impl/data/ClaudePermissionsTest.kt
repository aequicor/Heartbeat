package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ClaudePermissionsTest {
    @Test
    fun `cancel during initial persistence withdraws the permission and saves the resolved projection`() = runTest {
        var saves = 0
        val fixture = Fixture {
            saves++
            if (saves == 1) awaitCancellation()
        }
        val pending = async { fixture.permissions.request(APPROVAL) }
        runCurrent()
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.state).requests.single()
        pending.cancelAndJoin()
        val running = assertIs<ActiveSessionState.Running>(fixture.state)
        assertTrue(request.id in running.turn.resolvedPermissions)
        assertEquals(2, saves)
        fixture.permissions.respond(PermissionDecision(request.turn, request.id, request.options.first().id))
        assertEquals(2, saves)
    }

    @Test
    fun `close resolves unanswered permission ids and releases the waiting call`() = runTest {
        val fixture = Fixture {}
        val pending = async { fixture.permissions.request(APPROVAL) }
        runCurrent()
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.state).requests.single()
        fixture.permissions.close()
        assertFalse(pending.await())
        assertTrue(request.id in fixture.observer.turn.resolvedPermissions)
        assertFalse(fixture.permissions.request(APPROVAL))
    }

    @Test
    fun `user answer removes its pending entry before cancellation cleanup`() = runTest {
        var saves = 0
        val fixture = Fixture { saves++ }
        val pending = async { fixture.permissions.request(APPROVAL) }
        runCurrent()
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.state).requests.single()
        fixture.permissions.respond(PermissionDecision(request.turn, request.id, request.options.first().id))
        assertTrue(pending.await())
        assertEquals(2, saves)
        assertEquals(setOf(request.id), fixture.observer.turn.resolvedPermissions)
    }

    private class Fixture(persist: suspend () -> Unit) {
        var state: ActiveSessionState = ActiveSessionState.Ready()
        private val history = ClaudeHistory()
        val observer = ClaudeTurnObserver(
            SessionRef(testTarget.engine, SessionSourceId("test"), "native"),
            Turn(TurnId("turn"), prompt("request").id, testTarget),
            prompt("request"),
            history,
            CompletableDeferred(),
            { state = it },
        )
        val permissions = ClaudePermissions(observer, history, { true }, { state = it }, persist, { true })
    }
}

private val APPROVAL = AgentToolApproval("Bash", "Run command", "echo example")
