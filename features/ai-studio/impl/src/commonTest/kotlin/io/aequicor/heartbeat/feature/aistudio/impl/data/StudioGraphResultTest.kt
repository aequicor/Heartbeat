package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioGraphResultTest {
    @Test
    fun `partial output never replaces native failure evidence`() {
        val failure = EngineFailure.Unknown()
        val result = TurnOutcome.Failed(failure).graphResult("partial answer ".repeat(1000))

        assertEquals(GraphTaskPhase.Failed, result.phase)
        assertTrue(result.text.startsWith("Agent turn failed: ${failure.code}\n\npartial answer"))
        assertEquals(SchedulerLimits.MAX_PAYLOAD, result.text.length)
    }

    @Test
    fun `finished text cannot turn an unconfirmed native outcome into success`() {
        val result = TurnOutcome.Unknown.graphResult("finished")

        assertEquals(GraphTaskPhase.RecoveryRequired, result.phase)
        assertEquals("Native task outcome is not confirmed\n\nfinished", result.text)
        assertEquals("answer", TurnOutcome.Completed.graphResult("answer").text)
    }
}
