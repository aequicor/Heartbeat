package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.cell

/** Effect-port fake; exact native proof and restart behavior are tested against OrganicExecutorLifecycle. */
internal class TestOrganicExecutors(private val journal: OrganismJournal, private val sessions: CellSessions) :
    OrganicExecutors {
    override suspend fun load(): List<Organism> = journal.load()
    override suspend fun persist(organism: Organism) = journal.save(organism)
    override suspend fun hibernate(organism: Organism): OrganicRelease =
        release(organism, organism.cells.map { it.id }, ReleaseMode.Retire)
    override suspend fun reconcileJudges(organism: Organism) = Unit
    override suspend fun release(organism: Organism, cells: List<CellId>, mode: ReleaseMode): OrganicRelease {
        val stopped = cells.filter { id ->
            val cell = organism.cell(id) ?: return@filter true
            val release = if (cell.phase is CellPhase.Dead) ReleaseMode.Lyse else mode
            sessions.release(CellKey(organism.id, id), cell.session, release, cell.turns) == ReleaseOutcome.Stopped
        }.toSet()
        return OrganicRelease(stopped, cells.all { it in stopped })
    }
}
