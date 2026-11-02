package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionControl
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.SessionRelease
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicExecutorAddress
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeAttempt
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeClaim
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeStage
import io.aequicor.heartbeat.feature.organicai.impl.domain.cellExecution
import io.aequicor.heartbeat.feature.organicai.impl.domain.executor
import io.aequicor.heartbeat.feature.organicai.impl.domain.judgeExecution
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrganicExecutorLifecycleTest {
    @Test
    fun `sleep stops an accepted judge in an open case without permanent retirement`() = runTest {
        val fixture = Fixture(isJudge = true)
        fixture.prepare()
        fixture.facade.ownedStops = fixture.stops(expectedStatus = fixture.source.status)
        val result = fixture.hibernate()
        assertTrue(result.isConfirmed)
        assertEquals(listOf(fixture.ref to fixture.request), fixture.calls)
        assertTrue(fixture.receipt().isQuiescent)
        assertTrue(fixture.control.fenced.isEmpty())
        assertEquals(2, fixture.control.suspensions)
        assertFalse(fixture.nativeSession.isArchived)
        assertTrue(fixture.facade.resumptions.isEmpty())
        assertEquals(fixture.source.cases, fixture.restarted().read(fixture.source.id)?.cases)
    }

    @Test
    fun `cold release fences durable state and stops only the original request without resume`() = runTest {
        val fixture = Fixture()
        fixture.prepare()
        fixture.facade.ownedStops = fixture.stops()
        val result = fixture.release()
        assertTrue(result.isConfirmed)
        assertEquals(listOf(fixture.ref to fixture.request), fixture.calls)
        assertTrue(fixture.nativeSession.isArchived)
        assertTrue(fixture.facade.creations.isEmpty())
        assertTrue(fixture.facade.resumptions.isEmpty())
        assertEquals(SessionRelease.Released, fixture.restarted().read(fixture.source.id)?.sessions)
        assertFalse(fixture.stores.store.retentions.getValue("organism.o1").isPermanent)
    }

    @Test
    fun `unsupported cold Stop never becomes success from missing handles`() = runTest {
        val fixture = Fixture()
        fixture.prepare()
        assertFalse(fixture.release().isConfirmed)
        assertEquals(SessionRelease.Unconfirmed, fixture.restarted().read(fixture.source.id)?.sessions)
        assertTrue(fixture.stores.store.retentions.getValue("organism.o1").isPermanent)
        assertTrue(fixture.facade.creations.isEmpty())
        assertTrue(fixture.facade.resumptions.isEmpty())
    }

    @Test
    fun `pending native admission prevents Stop and release proof`() = runTest {
        val fixture = Fixture()
        fixture.prepare()
        fixture.control.hasPending = true
        fixture.facade.ownedStops = fixture.stops()
        assertFalse(fixture.release().isConfirmed)
        assertTrue(fixture.calls.isEmpty())
        assertEquals(OrganicNativeStage.Accepted, fixture.receipt().stage)
    }

    @Test
    fun `a mismatched native stop result cannot settle the request`() = runTest {
        val fixture = Fixture()
        fixture.prepare()
        fixture.facade.ownedStops = fixture.stops(RequestId("another-request"))
        assertFalse(fixture.release().isConfirmed)
        assertEquals(OrganicNativeStage.Accepted, fixture.receipt().stage)
    }

    @Test
    fun `a native ref without a matching receipt keeps whole release unconfirmed`() = runTest {
        val fixture = Fixture()
        fixture.prepare()
        fixture.facade.ownedStops = fixture.stops()
        fixture.control.extra = setOf(session("unaccounted"))
        assertFalse(fixture.release().isConfirmed)
        assertTrue(fixture.receipt().isQuiescent)
        assertEquals(SessionRelease.Unconfirmed, fixture.restarted().read(fixture.source.id)?.sessions)
    }

    @Test
    fun `aborting a human zygote never archives its conversation`() = runTest {
        val fixture = Fixture(isHuman = true)
        fixture.prepare()
        fixture.facade.ownedStops = fixture.stops()
        assertTrue(fixture.release().isConfirmed)
        assertFalse(fixture.nativeSession.isArchived)
        assertTrue(fixture.control.fenced.isEmpty())
    }

    private class Fixture(isJudge: Boolean = false, isHuman: Boolean = false) {
        val stores = KeyValueOrganismJournalTest.MemoryStores()
        private val journal = restarted()
        private val case = CaseId("open-case")
        val source = if (isJudge) {
            organism(cases = listOf(ImmuneCase.Complaint(case, CellId.ZYGOTE, CellId.ZYGOTE, "check")))
                .let { it.copy(cells = it.cells.map { cell -> cell.copy(session = null) }) }
        } else {
            organism().let {
                if (isHuman) {
                    it.copy(
                        conversation = LogicalSessionContext(LogicalSessionId("human"), SessionKind.Session),
                    )
                } else {
                    it
                }
            }
        }
        val ref = if (isJudge) session("judge") else source.cells.single().session!!
        val request = if (isJudge) {
            RequestId(
                "judge-request",
            )
        } else {
            (source.cells.single().phase as CellPhase.Working).request
        }
        private val incarnation = source.incarnation!!
        private val binding = if (isJudge) source.judgeExecution(case) else source.cellExecution(CellId.ZYGOTE)
        val nativeSession = FakeSession(ref)
        val facade = FakeFacade(stored = mapOf(ref to nativeSession))
        val control = Control()
        val calls = mutableListOf<Pair<SessionRef, RequestId>>()

        fun restarted() = KeyValueOrganismJournal(stores, KeyValueOrganismJournalTest.TestDispatchers)

        suspend fun prepare() {
            journal.save(source)
            var current = journal.reserve(
                source.id,
                incarnation,
                OrganicNativeClaim(
                    "claim",
                    binding?.instance?.id ?: source.executor(OrganicExecutorAddress.Kind.Cell, CellId.ZYGOTE.value).id,
                    binding,
                    TARGET,
                    null,
                    request,
                    "work",
                ),
            )
            for (stage in listOf(
                OrganicNativeStage.Opening,
                OrganicNativeStage.Opened,
                OrganicNativeStage.Entered,
                OrganicNativeStage.Accepted,
            )) {
                current = journal.advance(
                    source.id,
                    incarnation,
                    current,
                    current.copy(
                        stage = stage,
                        session = if (stage == OrganicNativeStage.Opening) null else ref,
                        turn = if (stage == OrganicNativeStage.Accepted) TurnId("facade-turn") else null,
                        revision = current.revision + 1,
                    ),
                )
            }
        }

        suspend fun receipt(): OrganicNativeAttempt = journal.attempts(source.id, incarnation).single()

        fun stops(resultRequest: RequestId = request, expectedStatus: OrganismStatus = OrganismStatus.Aborted) =
            object : StopsOwnedTurns {
                override suspend fun stop(ref: SessionRef, request: RequestId, access: OwnedTurnAccess): OwnedTurnStop {
                    assertEquals(expectedStatus, journal.read(source.id)?.status)
                    assertEquals(TARGET, access.target)
                    calls += ref to request
                    return OwnedTurnStop.Confirmed(
                        Turn(TurnId("native-turn"), resultRequest, TARGET, TurnOutcome.Cancelled),
                    )
                }
            }

        suspend fun hibernate() = OrganicExecutorLifecycle(journal, journal, control, facade, cellSessions(facade))
            .hibernate(source)

        suspend fun release() = OrganicExecutorLifecycle(
            journal,
            journal,
            control,
            facade,
            cellSessions(facade),
        ).release(
            source.copy(
                cells = source.cells.map { it.copy(phase = CellPhase.Dead(DeathCause.Aborted)) },
                status = OrganismStatus.Aborted,
                sessions = SessionRelease.Releasing,
                version = source.version + 1,
            ),
            listOf(CellId.ZYGOTE),
            ReleaseMode.Lyse,
        )
    }

    private class Control : SessionExecutionControl {
        val fenced = mutableSetOf<SessionExecutionBinding>()
        var suspensions = 0
        var hasPending = false
        var extra = emptySet<SessionRef>()
        override suspend fun fence(binding: SessionExecutionBinding, persist: suspend () -> Unit) {
            persist()
            fenced += binding
        }
        override suspend fun awaitAdmissions(binding: SessionExecutionBinding) {
            check(binding in fenced)
            check(!hasPending) { "Native admission remains pending" }
        }
        override suspend fun <T> suspended(binding: SessionExecutionBinding, block: suspend () -> T): T {
            check(!hasPending)
            suspensions++
            return block()
        }
        override suspend fun references(binding: SessionExecutionBinding): Set<SessionRef> = extra
    }
}
