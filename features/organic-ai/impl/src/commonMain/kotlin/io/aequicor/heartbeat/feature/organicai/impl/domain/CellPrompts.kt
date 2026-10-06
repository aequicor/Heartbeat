package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.cut
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismTools
import io.aequicor.heartbeat.feature.organicai.api.Work
import io.aequicor.heartbeat.feature.organicai.api.cell
import io.aequicor.heartbeat.feature.organicai.api.children
import io.aequicor.heartbeat.feature.organicai.api.depth
import io.aequicor.heartbeat.feature.organicai.api.isAlive
import io.aequicor.heartbeat.feature.organicai.api.isZygote

/** A turn carries host instructions and the user's genesis or follow-up text. Results are tool output only. */
internal fun turnPrompt(organism: Organism, cell: Cell): String {
    val phase = cell.phase as? CellPhase.Working ?: error("Only a working cell has a turn")
    val body = when (val work = phase.work) {
        Work.Genesis -> cell.task
        is Work.FollowUp -> work.text
        Work.CheckInbox, is Work.Letters -> ""
    }
    val directives = buildList {
        val isRoleNeeded = phase.work !is Work.FollowUp && (phase.isRecovery || phase.work == Work.Genesis)
        add(if (isRoleNeeded) role(organism, cell) else reminder(organism, cell))
        if (phase.isRecovery) add(RECOVERY)
        if (phase.work == Work.CheckInbox || phase.work is Work.Letters) add(CHECK_INBOX)
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
    appendLine("- ${OrganismTools.RECEIVE}: read results when ready; wait=true requests sleep if none are ready.")
    appendLine()
    append(RULES)
}

private fun reminder(organism: Organism, cell: Cell): String {
    val working = organism.children(cell.id).filter { it.isAlive }.joinToString { it.id.value }
    val open = organism.cases.filter { it.filedBy == cell.id }.joinToString { it.id.value }
    return "You are ${cell.label()} of an organic AI organism. " +
        (if (working.isEmpty()) "None of your children is alive. " else "Your children still alive: $working. ") +
        (if (open.isEmpty()) "" else "Your cases still open: $open. ") +
        RULES
}

private const val GOAL_CONTEXT_CHARS = 2_000

private const val CHECK_INBOX =
    "Continue by calling organism_receive to read your inbox. Results are never delivered as chat messages. " +
        "If this older session does not expose organism_receive, use organism_status with receive=true instead."

private const val RULES =
    "Results of children, verdicts and binding answers stay in your inbox. Call organism_receive when you are " +
        "ready to use them; continue independent work meanwhile. If nothing is ready and you need a result to " +
        "continue, call organism_receive with wait=true. End your turn only if it confirms a wait; the host then " +
        "resumes you with a reminder to read the inbox, after your current turn ends. Do not poll or sleep in a " +
        "loop. Read all pending results before your final answer. A final answer with no living children, open " +
        "cases or unread results completes your cell (the zygote completes the organism). Never ask the user " +
        "questions; actions that need approval wait for the user's decision. If organism_receive is unavailable " +
        "in an older session, call organism_status with receive=true and the same arguments."

private const val RECOVERY =
    "Heartbeat restarted or the user resumed your work. Your previous turn may have partly happened. Check " +
        "the current state before repeating an action. To replay inbox results after an uncertain tool delivery, " +
        "call organism_receive with after=0 and follow its next cursor."
