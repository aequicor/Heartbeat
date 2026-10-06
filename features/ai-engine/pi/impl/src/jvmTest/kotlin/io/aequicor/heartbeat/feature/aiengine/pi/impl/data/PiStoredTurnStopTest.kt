@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
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
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiStoredTurnStopTest {
    @Test
    fun `reused request id selects the active turn rather than an old terminal receipt`() = runTest {
        val setup = setup()
        setup.journal.finish(setup.turn.id, TurnOutcome.Completed)
        val current = setup.turn.copy(id = TurnId("reused"))
        setup.journal.begin(current, TrustLevel.Ask, Owner)
        val launch = StopLaunch { true }
        val stopped = assertNotNull(PiStoredTurnStop(setup.journal, launch).stop(prompt("request").id))
        assertEquals(current.id, stopped.turn.id)
        assertEquals(TurnOutcome.Unknown, stopped.turn.outcome)
        assertEquals(1, launch.stops)
    }

    @Test
    fun `fence survives completion until exit receipt is durable`() = runTest {
        val setup = setup()
        val exit = CompletableDeferred<Unit>()
        val launch = StopLaunch {
            exit.await()
            true
        }
        val stopping = async { PiStoredTurnStop(setup.journal, launch).stop(prompt("request").id) }
        runCurrent()
        assertNotNull(setup.journal.restore()?.stopping)
        setup.journal.finish(setup.turn.id, TurnOutcome.Completed)
        assertFailsWith<EngineException> { setup.journal.begin(setup.next(), TrustLevel.Ask, Owner) }
        exit.complete(Unit)
        val result = assertNotNull(stopping.await())
        assertEquals(TurnOutcome.Completed, result.turn.outcome)
        assertTrue(result.isProcessStopped)
        assertNull(setup.journal.restore()?.stopping)
        setup.journal.begin(setup.next(), TrustLevel.Ask, Owner.copy(launchId = "next"))
        assertEquals(result, PiStoredTurnStop(setup.journal, launch).stop(prompt("request").id))
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
        val stopping = async { PiStoredTurnStop(setup.journal, launch).stop(prompt("request").id) }
        runCurrent()
        stopping.cancelAndJoin()
        assertNotNull(setup.journal.restore()?.active)
        assertNotNull(setup.journal.restore()?.stopping)
        assertNull(setup.journal.restore()?.stopInspection)
        exit.complete(Unit)
        val result = PiStoredTurnStop(setup.journal, launch).stop(prompt("request").id)
        assertEquals(TurnOutcome.Unknown, result?.turn?.outcome)
        assertTrue(result?.isProcessStopped == true)
        assertEquals(2, launch.stops)
    }

    @Test
    fun `a concurrent descendant expansion prevents stale exit proof clearing the fence`() = runTest {
        val setup = setup()
        val stop = assertNotNull(setup.journal.fenceStop(prompt("request").id, setup.turn.id))
        assertTrue(setup.journal.inspectStop(stop, "first"))
        assertEquals(Owner, setup.journal.observeStop(stop, "first", Owner))
        assertTrue(setup.journal.inspectStop(stop, "second"))
        val expanded = Owner.copy(observedChildren = listOf(PiProcessIdentity(2, START)))
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
        val stop = assertNotNull(setup.journal.fenceStop(prompt("request").id, null))
        assertTrue(setup.journal.inspectStop(stop, "interrupted"))
        val snapshot = Json.decodeFromString<PiTurnSnapshot>(Json.encodeToString(setup.journal.restore()))
        assertEquals("interrupted", snapshot.stopInspection)
        assertFalse(setup.journal.inspectStop(stop, "retry"))
        assertNull(setup.journal.stopped(stop, Owner))
        assertNull(PiStoredTurnStop(setup.journal, StopLaunch { true }).stop(prompt("request").id))
        assertNotNull(setup.journal.restore()?.stopping)
    }

    @Test
    fun `wrong request turn or missing process evidence never invokes native stop`() = runTest {
        val setup = setup()
        val launch = StopLaunch { true }
        val stopper = PiStoredTurnStop(setup.journal, launch)
        assertNull(stopper.stop(RequestId("foreign")))
        assertNull(stopper.stop(prompt("request").id, TurnId("foreign")))
        assertEquals(0, launch.stops)
        val legacy = setup(owner = null)
        assertNull(PiStoredTurnStop(legacy.journal, launch).stop(prompt("request").id))
        assertEquals(0, launch.stops)
    }

    @Test
    fun `failure storing the fence prevents native stop and failure storing proof retains the fence`() = runTest {
        val records = GatedPiTurnRecords()
        val setup = setup(records = records)
        val launch = StopLaunch { true }
        records.beforeWrite = { if (it.stopping != null) error("Disk unavailable") }
        assertFailsWith<EngineException> { PiStoredTurnStop(setup.journal, launch).stop(prompt("request").id) }
        assertEquals(0, launch.stops)
        records.beforeWrite = { if (it.last?.isProcessStopped == true) error("Disk unavailable") }
        assertFailsWith<EngineException> { PiStoredTurnStop(setup.journal, launch).stop(prompt("request").id) }
        assertNotNull(setup.journal.restore()?.stopping)
        assertNull(setup.journal.restore()?.last)
        records.beforeWrite = {}
        assertTrue(PiStoredTurnStop(setup.journal, launch).stop(prompt("request").id)?.isProcessStopped == true)
    }

    private suspend fun TestScope.setup(
        owner: PiExecutionOwner? = Owner,
        records: PiTurnRecords = MemoryPiTurnRecords(),
    ): Setup {
        val fixture = fixture(this)
        val session = fixture.session
        val journal = PiTurnJournal(records, session.ref, session.route, "account")
        val turn = Turn(TurnId("turn"), prompt("request").id, RuntimeTarget)
        journal.begin(turn, TrustLevel.Ask, owner)
        return Setup(journal, turn)
    }

    private data class Setup(val journal: PiTurnJournal, val turn: Turn) {
        fun next(): Turn = turn.copy(id = TurnId("next"), request = RequestId("next"))
    }

    private class StopLaunch(private val exit: suspend () -> Boolean) : PiProcesses {
        var stops = 0
        override suspend fun credentialFingerprint(source: AuthSource.ManagedKey): String = error("Unused")
        override suspend fun transcript(nativeId: String): String? = error("Stop must never read transcript")
        override suspend fun start(
            source: AuthSource.ManagedKey,
            workspace: String?,
            event: suspend (JsonObject) -> Unit,
            failed: suspend (EngineFailure) -> Unit,
            plan: PiLaunchPlan?,
        ): PiConnection = error("Stop must never launch")
        override suspend fun stop(
            owner: PiExecutionOwner,
            beginInspection: suspend () -> Boolean,
            record: suspend (PiExecutionOwner) -> PiExecutionOwner?,
        ): Boolean {
            stops++
            return beginInspection() && record(owner) != null && exit()
        }
    }

    private companion object {
        const val START = "2026-10-06T00:00:00Z"
        val Owner = PiExecutionOwner("launch", PiProcessIdentity(1, START))
    }
}
