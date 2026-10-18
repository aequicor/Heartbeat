package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.Fence
import io.aequicor.heartbeat.feature.aiengine.facade.api.cut
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.Letter
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.VerdictOutcome
import io.aequicor.heartbeat.feature.organicai.api.cell

/**
 * Results of one tool call share a budget, so many large results cannot overflow the context of the turn. What other
 * sessions wrote is fenced, so a result cannot pose as another letter.
 */
internal fun inboxReport(
    organism: Organism,
    letters: List<Letter>,
    fence: Fence = Fence.random(),
    results: Map<CellId, CellResult> = emptyMap(),
): String {
    val share = (LETTERS_CHARS / letters.size.coerceAtLeast(1)).coerceAtLeast(MIN_LETTER_CHARS)
    return letters
        .mapIndexed { index, letter ->
            "Letter ${index + 1}: " + letter.render(organism, share, fence) + letter.integration(results)
        }
        .joinToString("\n\n")
}

/**
 * What the host says about the work of a finished child in a sandbox: where it lives and how to integrate it. This is
 * trusted host text outside the fence; nothing in it comes from the child.
 */
private fun Letter.integration(results: Map<CellId, CellResult>): String {
    val finished = this as? Letter.ChildFinished ?: return ""
    val result = results[finished.child] ?: return ""
    result.virtualWorkspace?.let { workspace ->
        return "\nHost note: this child's immutable virtual result is snapshot " +
            "${result.commit}, workspace ${workspace.value}. " +
            "Integrate it with sandbox_integrate using these exact workspace and snapshot " +
            "values, then validate with the project's run_build commands."
    }
    val base = result.base?.let { ", cut from $it" }.orEmpty()
    return "\nHost note: ${finished.child.value}'s work lives in its own sandbox branch ${result.branch}$base. " +
        "Everything in its checkout, committed or not, is captured as commit ${result.commit}. It is not part of " +
        "your checkout until you integrate it: commit your own pending work, run `git merge --no-ff " +
        "${result.commit}` in your checkout, resolve any conflicts keeping the intent of both sides, and verify " +
        "with run_build."
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
