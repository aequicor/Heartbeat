package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeRunOutcomesTest {
    @Test
    fun `an unconfirmed outcome after an explicit stop is the stop and reports no failure`() = runTest {
        var failures = 0
        val outcome = nativeRunOutcome(TurnOutcome.Unknown, isStopRequested = true) { failures++ }
        assertEquals(RunOutcome.Stopped, outcome)
        assertEquals(0, failures)
    }

    @Test
    fun `an unconfirmed outcome without a stop is a failure to report`() = runTest {
        var failures = 0
        val outcome = nativeRunOutcome(TurnOutcome.Unknown, isStopRequested = false) { failures++ }
        assertEquals(RunOutcome.Failed, outcome)
        assertEquals(1, failures)
    }

    @Test
    fun `a failed or missing outcome stays a failure even after a stop`() = runTest {
        var failures = 0
        assertEquals(
            RunOutcome.Failed,
            nativeRunOutcome(TurnOutcome.Failed(EngineFailure.Unknown()), isStopRequested = true) { failures++ },
        )
        assertEquals(RunOutcome.Failed, nativeRunOutcome(null, isStopRequested = true) { failures++ })
        assertEquals(2, failures)
    }

    @Test
    fun `confirmed outcomes map straight through`() = runTest {
        var failures = 0
        val completed = nativeRunOutcome(TurnOutcome.Completed, isStopRequested = true) { failures++ }
        val cancelled = nativeRunOutcome(TurnOutcome.Cancelled, isStopRequested = false) { failures++ }
        assertEquals(RunOutcome.Completed, completed)
        assertEquals(RunOutcome.Stopped, cancelled)
        assertEquals(0, failures)
    }
}
