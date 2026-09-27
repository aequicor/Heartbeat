package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test

class PermissionMachineTest {
    private val spec = activeSessionMachineSpec(ActiveSessionMachineKey("permissions"), ActiveSessionState.Ready())
    private val waiting = ActiveSessionState.AwaitingUserAction(TestTurn, listOf(TestPermission))
    private val decision = PermissionDecision(TestTurn.id, TestPermission.id, PermissionOptionId("allow"))

    @Test
    fun `native permission request becomes authoritative pending state`() {
        spec.assertTransition(
            ActiveSessionState.Running(TestTurn),
            ActiveSessionIntent.Internal.PermissionNeeded(TestPermission),
            waiting,
        )
        spec.assertIgnored(
            ActiveSessionState.Running(TestTurn),
            ActiveSessionIntent.Internal.PermissionNeeded(TestPermission.copy(turn = TurnId("foreign"))),
        )
        spec.assertIgnored(ActiveSessionState.Ready(), ActiveSessionIntent.Internal.PermissionNeeded(TestPermission))
    }

    @Test
    fun `decision remains pending until acknowledgement and cannot be submitted twice`() {
        val responding = waiting.copy(responding = setOf(TestPermission.id))
        spec.assertTransition(
            waiting,
            ActiveSessionIntent.Public.Decide(decision),
            responding,
            effects = listOf(ActiveSessionEffect.Decide(decision)),
        )
        spec.assertIgnored(responding, ActiveSessionIntent.Public.Decide(decision))
        spec.assertTransition(
            responding,
            ActiveSessionIntent.Internal.PermissionResolved(TestTurn.id, TestPermission.id),
            ActiveSessionState.Running(TestTurn.copy(resolvedPermissions = setOf(TestPermission.id))),
        )
    }

    @Test
    fun `foreign turns requests and options cannot authorize an action`() {
        listOf(
            decision.copy(turn = TurnId("foreign")),
            decision.copy(request = PermissionRequestId("foreign")),
            decision.copy(option = PermissionOptionId("foreign")),
        ).forEach { spec.assertIgnored(waiting, ActiveSessionIntent.Public.Decide(it)) }
        spec.assertIgnored(
            waiting,
            ActiveSessionIntent.Internal.PermissionResolved(TurnId("foreign"), TestPermission.id),
        )
        spec.assertIgnored(
            waiting,
            ActiveSessionIntent.Internal.PermissionResolved(TestTurn.id, PermissionRequestId("foreign")),
        )
    }

    @Test
    fun `multiple pending requests are independent and duplicate notifications are idempotent`() {
        val second = TestPermission.copy(id = PermissionRequestId("second"))
        val both = waiting.copy(requests = listOf(TestPermission, second))
        spec.assertTransition(waiting, ActiveSessionIntent.Internal.PermissionNeeded(second), both)
        spec.assertTransition(both, ActiveSessionIntent.Internal.PermissionNeeded(second), both)
        spec.assertTransition(
            both.copy(responding = setOf(second.id)),
            ActiveSessionIntent.Internal.PermissionResolved(TestTurn.id, second.id),
            waiting.copy(turn = TestTurn.copy(resolvedPermissions = setOf(second.id))),
        )
    }
}
