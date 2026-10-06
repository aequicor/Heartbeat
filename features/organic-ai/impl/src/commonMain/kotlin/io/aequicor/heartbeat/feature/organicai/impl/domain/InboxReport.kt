package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.Letter
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.VerdictOutcome
import io.aequicor.heartbeat.feature.organicai.api.cell

/**
 * Results of one tool call share a budget, so many large results cannot overflow the context of the turn. What other
 * sessions wrote is fenced, so a result cannot pose as another letter.
 */
internal fun inboxReport(organism: Organism, letters: List<Letter>, fence: Fence = Fence.random()): String {
    val share = (LETTERS_CHARS / letters.size.coerceAtLeast(1)).coerceAtLeast(MIN_LETTER_CHARS)
    return letters
        .mapIndexed { index, letter -> "Letter ${index + 1}: " + letter.render(organism, share, fence) }
        .joinToString("\n\n")
}

private fun Letter.render(organism: Organism, budget: Int, fence: Fence): String = when (this) {
    is Letter.ChildFinished ->
        "your child ${child.value} \"$name\" finished. Its result:\n${fence.quoted(result, budget)}"

    is Letter.ChildDied -> when (val death = cause) {
        // A judge wrote the reason of a lysis, so it is fenced like any other text of another session.
        is DeathCause.Lysed -> {
            val reason = fence.quoted(death.reason, budget)
            val case = death.case.value
            "your child ${child.value} \"$name\" was killed by the immune system (case $case). Reason:\n$reason"
        }

        else -> "your child ${child.value} \"$name\" ended without a result: ${death.describe()}."
    }

    // The binding answer takes half of the letter's budget, the question and the reason a quarter each.
    is Letter.DisputeResolved -> buildString {
        append("the immune system decided dispute ${case.value}.\nQuestion:\n")
        append(fence.quoted(question, budget / QUARTER))
        val binding = answer
        if (binding == null) {
            append("\nIt could not give a binding answer.")
        } else {
            append("\nBinding answer:\n").append(fence.quoted(binding, budget / HALF))
        }
        append("\nReason:\n").append(fence.quoted(reason, budget / QUARTER))
    }

    is Letter.Verdict -> {
        val subject = organism.cell(accused)?.label() ?: accused.value
        val fate = when (outcome) {
            VerdictOutcome.Killed -> "was killed together with its descendants"
            VerdictOutcome.Spared -> "was found healthy and lives on"
            VerdictOutcome.Moot -> "had already ended, so nothing was done"
            VerdictOutcome.Undecided -> "lives on: the immune system reached no decision"
        }
        "verdict on your complaint ${case.value}: $subject $fate.\nReason:\n${fence.quoted(reason, budget)}"
    }
}

/** Text another session wrote, cut to [budget] and fenced. */
private fun Fence.quoted(text: String, budget: Int): String = wrap(cut(text, budget))

private const val HALF = 2
private const val QUARTER = 4
private const val LETTERS_CHARS = 60_000
private const val MIN_LETTER_CHARS = 2_000
