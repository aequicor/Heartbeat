package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OwnedTurnStopTest {
    private val turn = Turn(
        TurnId("turn"),
        RequestId("request"),
        EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("model")),
    )

    @Test
    fun `confirmation cannot represent an active or uncorrelated turn`() {
        assertFailsWith<IllegalArgumentException> { OwnedTurnStop.Confirmed(turn) }
        assertFailsWith<IllegalArgumentException> {
            OwnedTurnStop.Confirmed(turn.copy(request = null, outcome = TurnOutcome.Completed))
        }
        val stopped = turn.copy(outcome = TurnOutcome.Unknown)
        assertEquals(stopped, OwnedTurnStop.Confirmed(stopped).turn)
    }
}
