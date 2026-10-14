package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiRuntimeShutdownTest {
    @Test
    fun `runtime shutdown stops an accepted turn and preserves its history and receipt`() = runTest {
        val records = MemoryPiTurnRecords()
        val fixture = runtimeFixture(this, records)
        val session = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val connection = fixture.processes.connections.single()
        connection.promptAck.complete(JsonObject(emptyMap()))
        val prompts = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts)).feature
        val history = assertIs<FeatureAccess.Available<SessionHistory>>(
            session.features.resolve(SessionHistory),
        ).feature
        val turn = prompts.send(prompt("disconnect"))
        val before = history.page(HistoryPageRequest()).items
        assertTrue(before.isNotEmpty())
        assertIs<ActiveSessionState.Running>(session.state.value)

        fixture.runtime.close()

        assertEquals(1, connection.stopRequests)
        assertTrue(connection.isClosed)
        assertIs<ActiveSessionState.Closed>(session.state.value)
        val saved = assertNotNull(records.get(session.ref))
        assertNull(saved.active)
        assertEquals(turn, saved.last?.turn?.id)
        assertEquals(prompt("disconnect").id, saved.last?.turn?.request)
        assertEquals(TurnOutcome.Unknown, saved.last?.turn?.outcome)
        assertEquals(before, history.page(HistoryPageRequest()).items)
    }

    @Test
    fun `runtime shutdown stops an accepted turn after its last handle is released`() = runTest {
        val records = MemoryPiTurnRecords()
        val fixture = runtimeFixture(this, records)
        val session = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val connection = fixture.processes.connections.single()
        connection.promptAck.complete(JsonObject(emptyMap()))
        val prompts = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts)).feature
        val turn = prompts.send(prompt("detached-disconnect"))
        session.close()
        assertFalse(connection.isClosed)
        assertEquals(0, connection.stopRequests)
        assertEquals(turn, records.get(session.ref)?.active?.turn?.id)

        fixture.runtime.close()

        assertEquals(1, connection.stopRequests)
        assertTrue(connection.isClosed)
        val saved = assertNotNull(records.get(session.ref))
        assertNull(saved.active)
        assertEquals(turn, saved.last?.turn?.id)
        assertEquals(TurnOutcome.Unknown, saved.last?.turn?.outcome)
        session.close()
        fixture.runtime.close()
        assertEquals(1, connection.stopRequests)
    }
}
