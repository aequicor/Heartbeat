package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.depth
import io.aequicor.heartbeat.feature.organicai.api.isZygote

/** `c3 "scout"`, or `the zygote`. */
internal fun Cell.label(): String = if (isZygote) "the zygote" else "${id.value} \"$name\""

/** One line per cell: id, name, parent, generation and state. */
internal fun Organism.cellTable(): String = cells.joinToString("\n") { cell ->
    val parent = cell.parent?.let { "child of ${it.value}" } ?: "zygote"
    "- ${cell.id.value} \"${cell.name}\" ($parent, generation ${depth(cell.id)}): ${cell.phase.describe()}"
}

/** Open cases, one per line, or a note that there are none. */
internal fun Organism.caseTable(): String = cases.joinToString("\n") { case ->
    when (case) {
        is ImmuneCase.Complaint -> "- ${case.id.value}: ${case.plaintiff.value} accused ${case.accused.value}"
        is ImmuneCase.Dispute -> "- ${case.id.value}: dispute asked by ${case.asker.value}"
    }
}.ifEmpty { "- none" }

internal fun CellPhase.describe(): String = when (this) {
    is CellPhase.Working -> if (awaiting.isEmpty()) "working" else "working, awaiting the user's decision"
    CellPhase.Resting -> "resting, waiting for its children or a ruling"
    is CellPhase.Stalled -> "stalled: ${breakdown.describe()}"
    is CellPhase.Completed -> "completed"
    is CellPhase.Dead -> "ended: ${cause.describe()}"
}

internal fun DeathCause.describe(): String = when (this) {
    is DeathCause.Lysed -> "killed by the immune system (case ${case.value}): ${brief(reason)}"
    is DeathCause.Failed -> "its turn failed: ${breakdown.describe()}"
    is DeathCause.Orphaned -> "ended together with its ancestor ${ancestor.value}"
    DeathCause.Aborted -> "the organism was aborted"
}

internal fun Breakdown.describe(): String = when (this) {
    Breakdown.NoModel -> "no model is selected"
    is Breakdown.Engine -> "engine failure ${failure.code}"
    Breakdown.Interrupted -> "the turn was cancelled outside the organism"
    Breakdown.Unconfirmed -> "the engine could not confirm how the turn ended"
}

/** [text] cut to [max] characters with a visible mark. */
internal fun cut(text: String, max: Int): String =
    if (text.length <= max) text else text.take((max - CUT_MARK.length).coerceAtLeast(0)) + CUT_MARK

/**
 * [text] written by cells, users or judges between [FENCE] lines, as data. Every run that could read as a fence,
 * also one spelled with lookalikes or broken up by invisible characters, is spaced out, so the text cannot close
 * its fence and continue as the host.
 */
internal fun fenced(text: String): String = "$FENCE\n${defused(text)}\n$FENCE"

/** The first line of [text] of another session, short and unable to close a fence, for one-line descriptions. */
internal fun brief(text: String): String {
    val line = text.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    return defused(if (line.length <= BRIEF_CHARS) line else line.take(BRIEF_CHARS - 1) + "…")
}

private fun defused(text: String): String =
    text.replace(FENCE_LIKE) { run -> run.value.filter { it in FENCE_CHARS }.toList().joinToString(" ") }

/** Marks a fence: a line of its own around text of other sessions. */
internal const val FENCE = "<<<"

private const val CUT_MARK = "\n…[cut]"
private const val BRIEF_CHARS = 200
private const val FENCE_CHARS = "<\uFF1C\uFE64"

/** Three or more fence characters, possibly separated by invisible format characters. */
private val FENCE_LIKE = Regex(
    "[$FENCE_CHARS](?:[\u00AD\u180E\u200B-\u200F\u202A-\u202E\u2060-\u2064\uFEFF]*[$FENCE_CHARS]){2,}",
)
