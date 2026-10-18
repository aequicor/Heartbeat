package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEffect
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineSpec
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.SessionRelease
import io.aequicor.heartbeat.feature.organicai.api.isStopped
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.cell
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellDriver
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellHarnesses
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellResult
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRun
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellSessions
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellWorkspaces
import io.aequicor.heartbeat.feature.organicai.impl.domain.ImmunityCourt
import io.aequicor.heartbeat.feature.organicai.impl.domain.JudgeSessions
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicAiEffects
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganismJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.SessionTranscripts
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the release protocol across the real facade adapter, effects, journal encoding and pure machine. */
class OrganicReleaseLifecycleTest {
    private val native = FakeSession(session("c1")).apply {
        finish = null
        ignoresCancel = true
    }
    private val cells = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native))))
    private val journal = EncodedJournal()
    private val sent = mutableListOf<OrganicAiIntent>()
    private val recorder = object : EffectScope<OrganicAiIntent> {
        override suspend fun send(intent: OrganicAiIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
    private var state = OrganicAiState.Living(mapOf(ORGANISM to organism(cell(C1))))

    @Test
    fun `an unconfirmed abort remains unconfirmed across sleep and can be retried`() = runTest {
        open()
        abort()
        assertEquals(SessionRelease.Unconfirmed, current().sessions)
        sleepAndRestore()
        assertEquals(SessionRelease.Unconfirmed, current().sessions)
        assertEquals(0, native.closes)
        assertFalse(native.isArchived)

        native.ignoresCancel = false
        abort()
        assertTrue(current().isStopped)
        assertEquals(1, native.closes)
        assertTrue(native.isArchived)
    }

    @Test
    fun `sleep before the abort effect cannot forget the running handle`() = runTest {
        open()
        val marked = checkNotNull(OrganicAiMachineSpec.resolve(state, OrganicAiIntent.Public.Abort(ORGANISM)))
        state = marked.to as OrganicAiState.Living
        sleepAndRestore()
        assertEquals(SessionRelease.Unconfirmed, current().sessions)
        assertEquals(0, native.closes)
        native.ignoresCancel = false
        abort()
        assertTrue(current().isStopped)
    }

    @Test
    fun `abort includes a previously lysed child whose native turn still runs`() = runTest {
        open()
        val dead = CellPhase.Dead(DeathCause.Lysed(CaseId("k1"), "killed"))
        state = OrganicAiState.Living(mapOf(ORGANISM to organism(cell(C1, phase = dead))))
        abort()
        assertFalse(current().isStopped)
        assertEquals(SessionRelease.Unconfirmed, current().sessions)
        native.ignoresCancel = false
        abort()
        assertTrue(current().isStopped)
    }

    @Test
    fun `a fresh profile restores an aborted organism without old process handles`() = runTest {
        open()
        abort()
        journal.save(current())
        val fresh = FacadeCellSessions(FakeFacade())
        effects(fresh).handle(OrganicAiEffect.Restore, recorder)
        val restored = (sent.last() as OrganicAiIntent.Internal.Restored).organisms.single()
        assertEquals(SessionRelease.Released, restored.sessions)
        // Finish the old test profile independently; the fresh profile must not adopt its handles.
        native.ignoresCancel = false
        cells.releaseAll()
    }

    private suspend fun open() {
        cells.open(CellKey(ORGANISM, C1), CellRoute(TARGET, null, null), null)
            .submit(RequestId("request"), "work", null)
    }

    private fun current(): Organism = state.organisms.getValue(ORGANISM)

    private suspend fun abort() {
        val transition = checkNotNull(OrganicAiMachineSpec.resolve(state, OrganicAiIntent.Public.Abort(ORGANISM)))
        state = transition.to as OrganicAiState.Living
        transition.effects.forEach { effects(cells).handle(it, recorder) }
        val feedback = sent.last() as OrganicAiIntent.Internal.SessionsReleased
        state = checkNotNull(OrganicAiMachineSpec.resolve(state, feedback)).to as OrganicAiState.Living
    }

    private suspend fun sleepAndRestore() {
        effects(cells).handle(OrganicAiEffect.Hibernate(listOf(current())), recorder)
        effects(cells).handle(OrganicAiEffect.Restore, recorder)
        val restored = sent.last() as OrganicAiIntent.Internal.Restored
        state = checkNotNull(
            OrganicAiMachineSpec.resolve(OrganicAiState.Awakening, restored),
        ).to as OrganicAiState.Living
    }

    private fun effects(sessions: CellSessions) = OrganicAiEffects(
        journal,
        { null },
        sessions,
        CellDriver(sessions, journal, NoWorkspaces, NoHarnesses),
        ImmunityCourt(JudgeSessions { _, _, _ -> error("No judgement expected") }, SessionTranscripts { emptyList() }),
        NoWorkspaces,
    )
}

private class EncodedJournal : OrganismJournal {
    private var snapshot: String? = null
    override suspend fun load(): List<Organism> = snapshot?.let {
        listOf(
            Json.decodeFromString<Organism>(it),
        )
    }.orEmpty()
    override suspend fun save(organism: Organism) {
        snapshot = Json.encodeToString(Organism.serializer(), organism)
    }
}

/** These lifecycle tests use shared workspaces and no harness choice. */
private object NoWorkspaces : CellWorkspaces {
    override suspend fun prepare(organism: Organism, cell: CellId): WorkspaceRef = error("No sandbox expected")
    override suspend fun begin(organism: Organism, cell: CellId, request: RequestId): CellRun =
        error("No sandbox expected")
    override suspend fun adopt(organism: Organism, cell: CellId, session: SessionRef, turn: TurnId): CellRun? =
        error("No sandbox expected")
    override suspend fun result(organism: Organism, cell: CellId): CellResult? = error("No sandbox expected")
    override suspend fun branches(organism: Organism): Map<CellId, String> = emptyMap()
}

private object NoHarnesses : CellHarnesses {
    override suspend fun connect(session: SessionRef, harnesses: Set<String>) = Unit
    override suspend fun disconnect(session: SessionRef, harnesses: Set<String>, isReleased: suspend () -> Boolean) =
        Unit
}
