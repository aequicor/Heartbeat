package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.Ruling
import io.aequicor.heartbeat.feature.organicai.api.cell
import kotlinx.coroutines.CancellationException

/**
 * The immune system. Every case is judged by a new session that sees only the dossier: the goal, the cells, the
 * case and recent transcripts of its subjects. Nothing of an earlier case reaches a later one, so a long-lived
 * organism never makes its judge's context rot.
 */
internal class ImmunityCourt(private val judges: JudgeSessions, private val transcripts: SessionTranscripts) {
    private val log = Log.tag("ImmunityCourt")

    suspend fun judge(organism: Organism, id: CaseId): Ruling {
        val case = organism.cases.firstOrNull { it.id == id } ?: return Ruling.None("The case is already closed.")
        val target = organism.immunityTarget ?: organism.target
            ?: return Ruling.None("The immune system has no model.")
        val dossier = case.subjects().associateWith { transcriptOf(organism.cell(it)) }
        log.i { "organism ${organism.id.value} case ${id.value}: judging in a fresh session" }
        val ruling = parseRuling(case, judges.deliberate(target, judgePrompt(organism, case, dossier)))
        log.i { "organism ${organism.id.value} case ${id.value}: ruling $ruling" }
        return ruling
    }

    /** A transcript the judge can still work without: a failed read is noted in the dossier. */
    private suspend fun transcriptOf(cell: Cell?): String {
        val session = cell?.session ?: return "(the cell has no session yet)"
        return try {
            renderTail(transcripts.recent(session), TRANSCRIPT_CHARS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "transcript of cell ${cell.id.value} is unavailable for the dossier" }
            "(the transcript could not be read)"
        }
    }

    private companion object {
        const val TRANSCRIPT_CHARS = 6_000
    }
}
