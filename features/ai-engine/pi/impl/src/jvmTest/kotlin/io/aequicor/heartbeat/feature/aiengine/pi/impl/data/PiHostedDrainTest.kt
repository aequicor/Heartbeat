@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiHostedDrainTest {
    @Test
    fun `drain resolves registration even when permission publication has not returned`() = runTest {
        val environment = piTestEnvironment(this)
        val permissions = mutableMapOf<PermissionRequestId, PermissionRequest>()
        val resolved = mutableListOf<PermissionRequestId>()
        val tools = PiHostedSessionTools(environment, { false }, { true }, permissions) { intent ->
            when (intent) {
                is ActiveSessionIntent.Internal.PermissionNeeded -> awaitCancellation()
                is ActiveSessionIntent.Internal.PermissionResolved -> resolved += intent.request
                else -> Unit
            }
            SendResult.Accepted
        }
        val turn = Turn(TurnId("turn"), prompt("request").id, RuntimeTarget)
        tools.beginTurn(turn)
        val request = PermissionRequest(
            PermissionRequestId("approval"),
            turn.id,
            "Approve",
            listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
        )
        backgroundScope.launch(backgroundScope.coroutineContext + checkNotNull(tools.lifetime)) {
            tools.approval(turn, request, PermissionOptionId("allow"))
        }
        runCurrent()
        assertEquals(listOf(request), permissions.values.toList())
        assertTrue(tools.jobs.drain(turn.id))
        assertTrue(permissions.isEmpty())
        assertEquals(listOf(request.id), resolved)
        tools.close()
    }
}
