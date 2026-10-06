@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexStoredTurnStopTest {
    @Test
    fun `fence survives completion and late acceptance until exit receipt is durable`() = runTest {
        val setup = setup()
        val exit = CompletableDeferred<Unit>()
        val launch = StopLaunch {
            exit.await()
            true
        }
        val stopping = async { CodexStoredTurnStop(setup.journal, launch).stop(Prompt.id) }
        runCurrent()
        assertNotNull(setup.journal.restore()?.stopping)
        setup.journal.finish(setup.turn.id, TurnOutcome.Completed)
        setup.journal.bind(setup.turn.id, "late-native")
        assertFailsWith<EngineException> { setup.journal.begin(setup.next(), TrustLevel.Ask, Owner) }
        exit.complete(Unit)
        val result = assertNotNull(stopping.await())
        assertEquals("late-native", result.nativeId)
        assertEquals(TurnOutcome.Completed, result.turn.outcome)
        assertTrue(result.isProcessStopped)
        assertNull(setup.journal.restore()?.stopping)
        setup.journal.begin(setup.next(), TrustLevel.Ask, Owner.copy(launchId = "next"))
        assertEquals(result, CodexStoredTurnStop(setup.journal, launch).stop(Prompt.id))
        assertEquals(1, launch.stops)
        assertEquals(setup.next().id, setup.journal.restore()?.active?.turn?.id)
    }

    @Test
    fun `cancelled exit wait can retry without resending and produces unknown only after proof`() = runTest {
        val setup = setup()
        val exit = CompletableDeferred<Unit>()
        val launch = StopLaunch {
            exit.await()
            true
        }
        val stopping = async { CodexStoredTurnStop(setup.journal, launch).stop(Prompt.id) }
        runCurrent()
        stopping.cancelAndJoin()
        assertNotNull(setup.journal.restore()?.active)
        assertNotNull(setup.journal.restore()?.stopping)
        assertNull(setup.journal.restore()?.stopInspection)
        exit.complete(Unit)
        val result = CodexStoredTurnStop(setup.journal, launch).stop(Prompt.id)
        assertEquals(TurnOutcome.Unknown, result?.turn?.outcome)
        assertTrue(result?.isProcessStopped == true)
        assertEquals(2, launch.stops)
    }

    @Test
    fun `a concurrent descendant expansion prevents stale exit proof clearing the fence`() = runTest {
        val setup = setup()
        val stop = assertNotNull(setup.journal.fenceStop(Prompt.id, setup.turn.id))
        assertTrue(setup.journal.inspectStop(stop, "first"))
        assertEquals(Owner, setup.journal.observeStop(stop, "first", Owner))
        assertTrue(setup.journal.inspectStop(stop, "second"))
        val expanded = Owner.copy(observedChildren = listOf(CodexProcessIdentity(2, START)))
        assertEquals(expanded, setup.journal.observeStop(stop, "second", expanded))
        assertNull(setup.journal.stopped(stop, Owner))
        assertNotNull(setup.journal.restore()?.stopping)
        assertTrue(setup.journal.inspectStop(stop, "third"))
        // A later observer cannot replace and forget the earlier child's identity.
        assertEquals(expanded, setup.journal.observeStop(stop, "third", Owner))
        assertTrue(setup.journal.stopped(stop, expanded)?.isProcessStopped == true)
    }

    @Test
    fun `incomplete discovery survives serialization and cannot become an empty successful retry`() = runTest {
        val setup = setup()
        val stop = assertNotNull(setup.journal.fenceStop(Prompt.id, null))
        assertTrue(setup.journal.inspectStop(stop, "interrupted"))
        val snapshot = Json.decodeFromString<CodexTurnSnapshot>(Json.encodeToString(setup.journal.restore()))
        assertEquals("interrupted", snapshot.stopInspection)
        assertFalse(setup.journal.inspectStop(stop, "retry"))
        assertNull(setup.journal.stopped(stop, Owner))
        assertNull(CodexStoredTurnStop(setup.journal, StopLaunch { true }).stop(Prompt.id))
        assertNotNull(setup.journal.restore()?.stopping)
    }

    @Test
    fun `wrong request turn or missing process evidence never invokes native stop`() = runTest {
        val setup = setup()
        val launch = StopLaunch { true }
        val stopper = CodexStoredTurnStop(setup.journal, launch)
        assertNull(stopper.stop(RequestId("foreign")))
        assertNull(stopper.stop(Prompt.id, TurnId("foreign")))
        assertEquals(0, launch.stops)
        val legacy = setup(owner = null)
        assertNull(CodexStoredTurnStop(legacy.journal, launch).stop(Prompt.id))
        assertEquals(0, launch.stops)
    }

    @Test
    fun `failure storing the fence prevents native stop and failure storing proof retains the fence`() = runTest {
        val records = GatedTurnRecords()
        val setup = setup(records = records)
        val launch = StopLaunch { true }
        records.beforeWrite = { if (it.stopping != null) error("Disk unavailable") }
        assertFailsWith<EngineException> { CodexStoredTurnStop(setup.journal, launch).stop(Prompt.id) }
        assertEquals(0, launch.stops)
        records.beforeWrite = { if (it.last?.isProcessStopped == true) error("Disk unavailable") }
        assertFailsWith<EngineException> { CodexStoredTurnStop(setup.journal, launch).stop(Prompt.id) }
        assertNotNull(setup.journal.restore()?.stopping)
        assertNull(setup.journal.restore()?.last)
        records.beforeWrite = {}
        assertTrue(CodexStoredTurnStop(setup.journal, launch).stop(Prompt.id)?.isProcessStopped == true)
    }

    private suspend fun TestScope.setup(
        owner: CodexExecutionOwner? = Owner,
        records: CodexTurnRecords = MemoryCodexTurnRecords(),
    ): Setup {
        val fixture = Fixture(this)
        val session = fixture.open()
        val journal = CodexTurnJournal(records, session.ref, session.route, "account")
        val turn = Turn(TurnId("turn"), Prompt.id, fixture.target)
        journal.begin(turn, TrustLevel.Ask, owner)
        return Setup(journal, turn)
    }

    private data class Setup(val journal: CodexTurnJournal, val turn: Turn) {
        fun next(): Turn = turn.copy(id = TurnId("next"), request = RequestId("next"))
    }

    private class StopLaunch(private val exit: suspend () -> Boolean) : PreparedCodexLaunch {
        var stops = 0
        override suspend fun open(): CodexWire = error("Stop must never launch")
        override suspend fun stop(
            owner: CodexExecutionOwner,
            beginInspection: suspend () -> Boolean,
            record: suspend (CodexExecutionOwner) -> CodexExecutionOwner?,
        ): Boolean {
            stops++
            return beginInspection() && record(owner) != null && exit()
        }
    }

    private companion object {
        const val START = "2026-10-06T00:00:00Z"
        val Owner = CodexExecutionOwner("launch", CodexProcessIdentity(1, START))
    }
}
