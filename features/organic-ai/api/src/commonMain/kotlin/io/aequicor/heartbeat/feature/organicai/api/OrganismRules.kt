package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId

/** Id the next divided cell gets. Cells are never removed, so ids are never reused. */
public fun Organism.nextCellId(): CellId = CellId("c${cells.size}")

/** Id the next filed case gets. */
public fun Organism.nextCaseId(): CaseId = CaseId("k${casesFiled + 1}")

/** Request id of [cell]'s [turn]; unique per organism, cell and turn number. */
public fun Organism.requestFor(cell: CellId, turn: Int): RequestId =
    RequestId("organic-${id.value}-${cell.value}-$turn")

/** Why [parent] may not divide now, or null when it may. */
public fun Organism.divisionRefusal(parent: CellId): Refusal? {
    val cell = cell(parent)
    val maxCells = limits.maxCells
    val maxDepth = limits.maxDepth
    return when {
        !isDeveloping -> Refusal.NotDeveloping
        cell == null -> Refusal.UnknownCell
        cell.phase !is CellPhase.Working -> Refusal.NotWorking
        maxCells != null && cells.size >= maxCells -> Refusal.TooManyCells
        maxDepth != null && depth(parent) + 1 > maxDepth -> Refusal.TooDeep
        else -> null
    }
}

/** Why [plaintiff] may not accuse [accused] now, or null when it may. The zygote is never accused. */
public fun Organism.complaintRefusal(plaintiff: CellId, accused: CellId): Refusal? {
    val filer = cell(plaintiff)
    val suspect = cell(accused)
    return when {
        !isDeveloping -> Refusal.NotDeveloping

        filer == null || suspect == null -> Refusal.UnknownCell

        filer.phase !is CellPhase.Working -> Refusal.NotWorking

        plaintiff == accused -> Refusal.SelfComplaint

        suspect.isZygote -> Refusal.ZygoteImmune

        !suspect.isAlive -> Refusal.CellNotAlive

        cases.any { it is ImmuneCase.Complaint && it.plaintiff == plaintiff && it.accused == accused } ->
            Refusal.DuplicateComplaint

        else -> null
    }
}

/** Why [asker] may not open a dispute with [parties] now, or null when it may. */
public fun Organism.disputeRefusal(asker: CellId, parties: List<CellId>): Refusal? {
    val filer = cell(asker)
    return when {
        !isDeveloping -> Refusal.NotDeveloping

        filer == null || parties.any { cell(it) == null } -> Refusal.UnknownCell

        filer.phase !is CellPhase.Working -> Refusal.NotWorking

        parties.size > OrganismBounds.MAX_PARTIES || parties.distinct().size != parties.size || asker in parties ->
            Refusal.InvalidParties

        else -> null
    }
}
