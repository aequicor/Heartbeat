package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest

/** The progenitor of every other cell: the one cell without a parent. */
public val Organism.zygote: Cell get() = cells.first(Cell::isZygote)

/** Whether this cell is the zygote. */
public val Cell.isZygote: Boolean get() = parent == null

/** Working, resting and stalled cells are alive; completed and dead ones have ended. */
public val Cell.isAlive: Boolean
    get() = when (phase) {
        is CellPhase.Working, CellPhase.Resting, is CellPhase.Stalled -> true
        is CellPhase.Completed, is CellPhase.Dead -> false
    }

/** Whether the organism still has living cells working towards its goal. */
public val Organism.isDeveloping: Boolean get() = status == OrganismStatus.Developing

/** The cell [id], or null when the organism has none. */
public fun Organism.cell(id: CellId): Cell? = cells.firstOrNull { it.id == id }

/** Direct children of [id]. */
public fun Organism.children(id: CellId): List<Cell> = cells.filter { it.parent == id }

/** [id] and every descendant of it, parents before children; empty for an unknown cell. */
public fun Organism.subtree(id: CellId): List<Cell> {
    val result = mutableListOf<Cell>()
    var generation = listOfNotNull(cell(id))
    while (generation.isNotEmpty()) {
        result += generation
        val parents = generation.mapTo(HashSet()) { it.id }
        generation = cells.filter { it.parent in parents && it !in result }
    }
    return result
}

/** [id], its parent and so on up to the zygote; empty for an unknown cell. */
public fun Organism.lineage(id: CellId): List<Cell> {
    val result = mutableListOf<Cell>()
    var next = cell(id)
    while (next != null && next !in result) {
        result += next
        next = next.parent?.let(::cell)
    }
    return result
}

/** Generations between [id] and the zygote (0 for the zygote). */
public fun Organism.depth(id: CellId): Int = (lineage(id).size - 1).coerceAtLeast(0)

/** Whether [id] must wait for living children, a case it filed or an open dispute it participates in. */
public fun Organism.isWaiting(id: CellId): Boolean = children(id).any(Cell::isAlive) || cases.any {
    it.filedBy == id || (it is ImmuneCase.Dispute && id in it.parties)
}

/**
 * The session of the sub-session [key] (a cell id, or a case id for its last judge) and how to reopen it as the
 * organism opened it: a cell on the organism's model and project with the detached hosted tools, a judge on the
 * immune model with neither. Reopening it so never changes what a cell may do. Null while it has no session.
 */
public fun Organism.sessionOf(key: String): OrganismSession? {
    cells.firstOrNull { it.id.value == key }?.let { cell ->
        val ref = cell.session ?: return null
        val target = target ?: return null
        return OrganismSession(ref, ResumeSessionRequest(target, workspace, areDetachedToolsEnabled = true))
    }
    val judge = trials.firstOrNull { it.case.id.value == key }?.judge ?: return null
    val target = immunityTarget ?: target ?: return null
    return OrganismSession(judge, ResumeSessionRequest(target, workspace = null, areDetachedToolsEnabled = false))
}
