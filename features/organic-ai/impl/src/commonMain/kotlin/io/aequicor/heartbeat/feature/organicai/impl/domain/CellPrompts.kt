package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.Letter
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismTools
import io.aequicor.heartbeat.feature.organicai.api.VerdictOutcome
import io.aequicor.heartbeat.feature.organicai.api.Work
import io.aequicor.heartbeat.feature.organicai.api.cell
import io.aequicor.heartbeat.feature.organicai.api.children
import io.aequicor.heartbeat.feature.organicai.api.depth
import io.aequicor.heartbeat.feature.organicai.api.isAlive
import io.aequicor.heartbeat.feature.organicai.api.isZygote

/**
 * The prompt of [cell]'s current turn. The host's role and rules travel in directive blocks; the task and the
 * letters, written by the user or by other cells, are escaped user text, so they cannot pose as host directives.
 * A genesis turn and a recovery turn carry the whole role, a letters turn a reminder of it.
 */
internal fun turnPrompt(organism: Organism, cell: Cell): String {
    val phase = cell.phase as? CellPhase.Working ?: error("Only a working cell has a turn")
    val body = when (val work = phase.work) {
        Work.Genesis -> cell.task
        is Work.Letters -> letters(organism, work.letters)
    }
    val directives = when {
        phase.isRecovery -> listOf(role(organism, cell), RECOVERY)
        phase.work == Work.Genesis -> listOf(role(organism, cell))
        else -> listOf(reminder(organism, cell))
    }
    return withHostDirectives(body, directives = emptyList(), leading = directives)
}

private fun role(organism: Organism, cell: Cell): String = buildString {
    appendLine(
        "You are ${cell.label()} of an organic AI organism: a group of agent sessions (cells) that grew from " +
            "one zygote and work towards a shared goal.",
    )
    if (cell.isZygote) {
        appendLine("Your task, in this message, is the organism's goal. You answer for the whole organism.")
    } else {
        val parent = cell.parent?.let { organism.cell(it) }
        appendLine("Your id: ${cell.id.value}. Your parent: ${parent?.label() ?: "unknown"}.")
        appendLine("Generation: ${organism.depth(cell.id)}. Your task is in this message.")
        appendLine("The organism's goal, for context:")
        appendLine(cut(organism.goal, GOAL_CONTEXT_CHARS))
    }
    appendLine()
    appendLine("Organism tools:")
    appendLine(
        "- ${OrganismTools.DIVIDE}: split off a child cell for a substantial, self-contained subtask that can run " +
            "in parallel. The child does not see your conversation: give it everything it needs.",
    )
    appendLine(
        "- ${OrganismTools.COMPLAIN}: report a cancerous cell: one that loops without progress, sabotages, takes " +
            "destructive actions, fabricates results or works against the goal. A fresh immune session judges the " +
            "case; a killed cell ends with its descendants. The zygote cannot be accused.",
    )
    appendLine(
        "- ${OrganismTools.DISPUTE}: ask the immune system for a binding answer when cells disagree or a " +
            "contested decision blocks you. Name the other cells concerned.",
    )
    appendLine("- ${OrganismTools.STATUS}: see the cells, their state and the open cases.")
    appendLine()
    append(RULES)
}

private fun reminder(organism: Organism, cell: Cell): String {
    val working = organism.children(cell.id).filter { it.isAlive }.joinToString { it.id.value }
    return "You are ${cell.label()} of an organic AI organism; below are letters the organism delivered to you. " +
        (if (working.isEmpty()) "None of your children is alive. " else "Your children still alive: $working. ") +
        RULES
}

/** Letters of one turn share a budget, so many large results cannot overflow the context of the turn. */
private fun letters(organism: Organism, letters: List<Letter>): String {
    val share = (LETTERS_CHARS / letters.size.coerceAtLeast(1)).coerceAtLeast(MIN_LETTER_CHARS)
    return letters
        .mapIndexed { index, letter -> "Letter ${index + 1}: " + cut(letter.render(organism), share) }
        .joinToString("\n\n")
}

private fun Letter.render(organism: Organism): String = when (this) {
    is Letter.ChildFinished -> "your child ${child.value} \"$name\" finished. Its result:\n$result"

    is Letter.ChildDied -> "your child ${child.value} \"$name\" ended without a result: ${cause.describe()}."

    is Letter.DisputeResolved -> buildString {
        append("the immune system decided dispute ${case.value}.\nQuestion: ").append(question)
        if (answer == null) {
            append("\nIt could not give a binding answer.")
        } else {
            append("\nBinding answer: ").append(answer)
        }
        append("\nReason: ").append(reason)
    }

    is Letter.Verdict -> {
        val subject = organism.cell(accused)?.label() ?: accused.value
        val fate = when (outcome) {
            VerdictOutcome.Killed -> "was killed together with its descendants"
            VerdictOutcome.Spared -> "was found healthy and lives on"
            VerdictOutcome.Moot -> "had already ended, so nothing was done"
            VerdictOutcome.Undecided -> "lives on: the immune system reached no decision"
        }
        "verdict on your complaint ${case.value}: $subject $fate.\nReason: $reason"
    }
}

private const val GOAL_CONTEXT_CHARS = 2_000
private const val LETTERS_CHARS = 60_000
private const val MIN_LETTER_CHARS = 2_000

private const val RULES =
    "Tool results are only receipts: results of children, verdicts and binding answers arrive later as a new " +
        "message after you end your turn, so never wait or poll for them. When you have started work you depend " +
        "on, end your turn briefly. When you end a turn while none of your children is alive and no dispute of " +
        "yours is open, your final message is your result for your parent (for the zygote: the organism's answer), " +
        "so make it complete and self-contained. Never ask the user questions; an action that needs approval " +
        "waits for the user's decision."

private const val RECOVERY =
    "Heartbeat restarted while your previous turn was running, so that turn may have partly happened. Check the " +
        "current state of your work before repeating any action, then continue. The work of that turn follows."
