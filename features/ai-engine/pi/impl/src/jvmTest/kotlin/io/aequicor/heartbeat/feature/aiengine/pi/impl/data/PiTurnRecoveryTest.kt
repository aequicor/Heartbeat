package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PiTurnRecoveryTest {
    @Test
    fun `fresh native busy snapshot never treats a recreated ready handle as idle`() = runTest {
        val f = fixture(this)
        val recovery = (f.session.features.resolve(RestoresSessionTurns) as FeatureAccess.Available).feature
        f.connection.isStreaming = true
        assertEquals(TurnInspection.Unknown, recovery.inspect(null))
        f.connection.isStreaming = false
        assertEquals(TurnInspection.Idle, recovery.inspect(null))
        f.session.shutdown()
    }
}
