package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnedCellTurnsTest {
    private val request = RequestId("organic")
    private val organic = Turn(TurnId("organic-turn"), request, TARGET)
    private val human = Turn(TurnId("human-turn"), RequestId("human"), TARGET)

    @Test
    fun `live stop confirms the original request without a cold capability`() = runTest {
        val active = FakeSession(session("live"))
        active.state.value = ActiveSessionState.Running(organic)
        val proof = assertIs<OwnedTurnStop.Confirmed>(active.stopOwned(request))
        assertEquals(request, proof.turn.request)
        assertEquals(listOf(organic.id), active.cancelled)
        assertEquals(0, active.closes)
    }

    @Test
    fun `a newer human request is never cancelled`() = runTest {
        val active = FakeSession(session("live"))
        active.state.value = ActiveSessionState.Running(human)
        assertEquals(OwnedTurnStop.Unconfirmed, active.stopOwned(request))
        assertTrue(active.cancelled.isEmpty())
        assertEquals(human, active.state.value.activeTurn())
    }

    @Test
    fun `replacement between inspection and cancellation does not cancel the new turn`() = runTest {
        val active = FakeSession(session("live"))
        active.state.value = ActiveSessionState.Running(organic)
        active.beforeCancel = { active.state.value = ActiveSessionState.Running(human) }
        assertEquals(OwnedTurnStop.Unconfirmed, active.stopOwned(request))
        assertTrue(active.cancelled.isEmpty())
        assertEquals(human, active.state.value.activeTurn())
    }

    @Test
    fun `closing a handle does not prove the request stopped`() = runTest {
        val active = FakeSession(session("live"))
        active.state.value = ActiveSessionState.Closed
        assertEquals(OwnedTurnStop.Unconfirmed, active.stopOwned(request))
    }
}
