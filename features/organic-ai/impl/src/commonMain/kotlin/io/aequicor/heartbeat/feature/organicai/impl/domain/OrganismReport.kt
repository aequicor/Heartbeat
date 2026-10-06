package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.cut
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.Refusal
import io.aequicor.heartbeat.feature.organicai.api.cell
import io.aequicor.heartbeat.feature.organicai.api.isAlive
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping

/** The living cell of a developing organism that runs in [session]; the session identity is trusted host data. */
internal fun OrganicAiState.Living.locate(session: SessionRef): CellAddress? = organisms.values.asSequence()
    .filter { it.isDeveloping }
    .firstNotNullOfOrNull { organism ->
        organism.cells.firstOrNull { it.session == session && it.isAlive }?.let { CellAddress(organism.id, it.id) }
    }

/** What `organism_status` shows to [viewer]: the goal, every cell and the open cases. */
internal fun statusReport(organism: Organism, viewer: CellId): String = buildString {
    appendLine("You are ${organism.cell(viewer)?.label() ?: viewer.value}.")
    val cell = organism.cell(viewer)
    if (cell != null) {
        appendLine("Unread results: ${cell.inbox.size - cell.receivedLetters}. Read them with organism_receive.")
    }
    appendLine("Organism goal:").appendLine(cut(organism.goal, GOAL_CHARS))
    appendLine("Cells:").appendLine(organism.cellTable())
    append("Open cases:\n").append(organism.caseTable())
}

/** Why a request of a cell was refused, in words for the agent. */
internal fun Refusal.explain(): String = when (this) {
    Refusal.NotDeveloping -> "the organism has already completed or was aborted"
    Refusal.UnknownCell -> "no such cell in this organism; use organism_status to see the cell ids"
    Refusal.NotWorking -> "only a cell in the middle of its turn can do this"
    Refusal.CellNotAlive -> "that cell has already ended"
    Refusal.ZygoteImmune -> "the zygote cannot be accused"
    Refusal.SelfComplaint -> "a cell cannot accuse itself"
    Refusal.DuplicateComplaint -> "you already have an open complaint against that cell"
    Refusal.TooManyCells -> "the organism has reached its limit of cells; finish the work with the cells you have"
    Refusal.TooDeep -> "the organism has reached its limit of generations; do this subtask yourself"
    Refusal.TooManyCases -> "the organism has reached its limit of immune cases; settle this yourself"
    Refusal.TooManyOpenCases -> "you have as many open cases as a cell may have; wait for their decisions first"
    Refusal.InvalidParties -> "parties must be up to ${OrganismBounds.MAX_PARTIES} distinct other cells"
}

private const val GOAL_CHARS = 1_000
