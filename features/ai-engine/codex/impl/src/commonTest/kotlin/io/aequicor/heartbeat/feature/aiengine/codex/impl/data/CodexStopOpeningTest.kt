@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexStopOpeningTest {
    @Test
    fun `unconfirmed stop prevents cold execution launch including after interrupted discovery`() = runTest {
        val setup = setup()
        val stop = assertNotNull(setup.journal.fenceStop(Prompt.id, null))
        setup.first.runtime.close()
        for (inspection in listOf(false, true)) {
            if (inspection) assertTrue(setup.journal.inspectStop(stop, "interrupted"))
            val second = Fixture(this, turns = setup.records)
            assertBusy { second.runtime.attach(setup.session.ref, ResumeSessionRequest(second.target)) }
            assertTrue(second.wire.peers.isEmpty())
            assertTrue(second.wire.written.none { it.text("method") in EXECUTION_METHODS })
            second.runtime.close()
        }
    }

    @Test
    fun `native completion cannot bypass stop fence but a durable exit receipt permits resume`() = runTest {
        val setup = setup()
        val stop = assertNotNull(setup.journal.fenceStop(Prompt.id, null))
        setup.journal.finish(stop.turn.id, TurnOutcome.Completed)
        setup.first.runtime.close()
        val second = Fixture(this, turns = setup.records)
        assertBusy { second.runtime.attach(setup.session.ref, ResumeSessionRequest(second.target)) }
        assertTrue(second.wire.peers.isEmpty())
        assertNull(setup.journal.restore()?.active)
        assertNotNull(setup.journal.restore()?.stopping)

        assertNotNull(setup.journal.stopped(stop, OWNER))
        val resumed = second.runtime.attach(setup.session.ref, ResumeSessionRequest(second.target))
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(resumed.state.value).lastTurn?.outcome)
        assertEquals(1, second.wire.peers.size)
        assertTrue(second.wire.written.none { it.text("method") == "turn/start" })
        second.runtime.close()
    }

    @Test
    fun `stop fence installed during configuration prevents resume and closes the candidate`() = runTest {
        val setup = setup()
        setup.first.runtime.close()
        val second = Fixture(this, turns = setup.records)
        second.beforeSearchLookup = { assertNotNull(setup.journal.fenceStop(Prompt.id, null)) }
        assertBusy { second.runtime.attach(setup.session.ref, ResumeSessionRequest(second.target)) }
        assertTrue(second.wire.peers.single().isClosed)
        assertTrue(second.wire.written.none { it.text("method") in EXECUTION_METHODS })
        assertNotNull(setup.journal.restore()?.stopping)
        second.runtime.close()
    }

    @Test
    fun `retained idle session cannot submit again while completed turn still has a stop fence`() = runTest {
        val setup = setup()
        assertNotNull(setup.journal.fenceStop(Prompt.id, null))
        setup.first.event(
            "turn/completed",
            "turn" to json("id" to "native-turn".json(), "status" to "completed".json()),
        )
        runCurrent()
        assertIs<ActiveSessionState.Ready>(setup.session.state.value)
        val peers = setup.first.wire.peers.size
        val starts = setup.first.wire.written.count { it.text("method") == "turn/start" }
        assertBusy { setup.session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next"))) }
        assertEquals(peers, setup.first.wire.peers.size)
        assertEquals(starts, setup.first.wire.written.count { it.text("method") == "turn/start" })
        assertEquals(Prompt.id, setup.journal.restore()?.last?.turn?.request)
        setup.first.runtime.close()
    }

    private suspend fun TestScope.setup(): Setup {
        val records = MemoryCodexTurnRecords()
        val first = Fixture(this, turns = records)
        val session = first.open()
        first.wire.peers.single().owner = OWNER
        session.feature(SendsPrompts).send(Prompt)
        val journal = CodexTurnJournal(records, session.ref, session.route, first.runtime.turnOwnership)
        return Setup(records, first, session, journal)
    }

    private suspend fun assertBusy(block: suspend () -> Unit) {
        val error = assertFailsWith<EngineException> { block() }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), error.failure)
    }

    private data class Setup(
        val records: CodexTurnRecords,
        val first: Fixture,
        val session: ActiveSession,
        val journal: CodexTurnJournal,
    )

    private companion object {
        val OWNER = CodexExecutionOwner("launch", CodexProcessIdentity(1, "2026-10-06T00:00:00Z"))
        val EXECUTION_METHODS = setOf("thread/start", "thread/resume", "turn/start")
    }
}
