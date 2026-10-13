package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioPermissionDeliveryTest {
    private val turn = Turn(TurnId("turn"), null, EngineTarget(EngineId("engine"), EngineBindingId("b"), ModelId("m")))
    private val request = PermissionRequest(
        PermissionRequestId("request"),
        turn.id,
        "Allow?",
        listOf(PermissionOption(PermissionOptionId("yes"), "Yes")),
    )
    private val decision = PermissionDecision(turn.id, request.id, PermissionOptionId("yes"))
    private fun pending() = MutableStateFlow<ActiveSessionState>(
        ActiveSessionState.AwaitingUserAction(turn, listOf(request)),
    )

    @Test
    fun `command admission is not acknowledgement and a late failure reaches the waiter`() = runTest {
        val state = pending()
        val result = async { assertFailsWith<IllegalStateException> { deliverStudioPermission(state, decision) {} } }
        runCurrent()
        assertFalse(result.isCompleted)
        state.value = ActiveSessionState.Unavailable(EngineFailure.Unknown(), activeTurn = turn)
        runCurrent()
        result.await()
    }

    @Test
    fun `only the exact turn and request acknowledges delivery including synchronous acknowledgement`() = runTest {
        val state = pending()
        val result = async { deliverStudioPermission(state, decision) {} }
        runCurrent()
        state.value = ActiveSessionState.Running(turn.copy(resolvedPermissions = setOf(PermissionRequestId("other"))))
        runCurrent()
        assertFalse(result.isCompleted)
        state.value = ActiveSessionState.Running(turn.copy(resolvedPermissions = setOf(request.id)))
        result.await()
        val immediate = pending()
        deliverStudioPermission(immediate, decision) {
            immediate.value = ActiveSessionState.Running(turn.copy(resolvedPermissions = setOf(request.id)))
        }
    }

    @Test
    fun `unavailable retains only unacknowledged permissions of the same turn`() {
        val permissions = listOf(request.toStudio("s"))
        val unavailable = ActiveSessionState.Unavailable(EngineFailure.Unknown(), activeTurn = turn)
        assertEquals(permissions, projectStudioPermissions("s", unavailable, permissions))
        assertTrue(
            projectStudioPermissions(
                "s",
                unavailable.copy(activeTurn = turn.copy(id = TurnId("next"))),
                permissions,
            ).isEmpty(),
        )
        assertTrue(
            projectStudioPermissions(
                "s",
                unavailable.copy(activeTurn = turn.copy(resolvedPermissions = setOf(request.id))),
                permissions,
            ).isEmpty(),
        )
        assertTrue(projectStudioPermissions("s", ActiveSessionState.Closed, permissions).isEmpty())
    }

    @Test
    fun `an observed request cannot target a reused id in a later native turn`() {
        val next = turn.copy(id = TurnId("next"))
        val pending = ActiveSessionState.AwaitingUserAction(next, listOf(request.copy(turn = next.id)))
        assertFailsWith<IllegalArgumentException> { requirePermissionTurn(pending, turn.id.value) }
        requirePermissionTurn(pending, next.id.value)
    }

    @Test
    fun `cancelling the waiter releases its subscription`() = runTest {
        val state = pending()
        val result = async { deliverStudioPermission(state, decision) {} }
        runCurrent()
        assertEquals(1, state.subscriptionCount.value)
        result.cancel()
        runCurrent()
        assertEquals(0, state.subscriptionCount.value)
    }

    @Test
    fun `terminal closed or replaced turns do not leave a waiter hanging`() = runTest {
        val endings = listOf(
            ActiveSessionState.Ready(turn.copy(outcome = TurnOutcome.Completed)),
            ActiveSessionState.Closed,
            ActiveSessionState.Running(turn.copy(id = TurnId("next"), resolvedPermissions = setOf(request.id))),
        )
        for (ending in endings) {
            val state = pending()
            val result = async {
                assertFailsWith<IllegalStateException> {
                    deliverStudioPermission(
                        state,
                        decision,
                    ) {}
                }
            }
            runCurrent()
            state.value = ending
            result.await()
        }
    }
}
