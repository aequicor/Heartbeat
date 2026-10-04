package io.aequicor.heartbeat.feature.organicai.api

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

/** Whether [id] must wait before its answer is final: a child is alive or its own dispute is open. */
public fun Organism.isWaiting(id: CellId): Boolean =
    children(id).any(Cell::isAlive) || cases.any { it is ImmuneCase.Dispute && it.asker == id }
