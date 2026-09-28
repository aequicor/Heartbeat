package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionInput
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Choice
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId

/** Option chosen for a questionnaire answer and the structured answer sent with it. */
internal data class StudioDecision(val optionId: String, val answer: StudioPermissionAnswer?)

/** Profile-unique question id of this permission. */
internal fun StudioPermission.questionnaireId() = QuestionnaireId("$sessionId/$requestId")

/**
 * The permission as a question of its session; null when it cannot be answered (no options, or an input without
 * a submit option). Without input two options become a yes/no confirmation and any other number a single choice
 * among them. With input the first non-skip option submits the answer and the skip option, when offered, skips it.
 */
internal fun StudioPermission.toQuestionnaire(): Questionnaire? {
    val question = when (val input = input) {
        null -> when {
            options.isEmpty() -> return null
            options.size == 2 -> Question.Confirm(options[0].title, options[1].title)
            else -> Question.SingleChoice(options.toChoices())
        }

        is StudioPermissionInput.SingleChoice -> Question.SingleChoice(input.choices.toChoices())

        is StudioPermissionInput.MultiChoice -> Question.MultiChoice(input.choices.toChoices(), input.min, input.max)

        is StudioPermissionInput.FreeText -> Question.FreeText(input.placeholder, input.isMultiline)
    }
    if (input != null && submitOption() == null) return null
    return Questionnaire(
        id = questionnaireId(),
        source = sessionId,
        title = title,
        question = question,
        description = description,
        isSkippable = input != null && skipOption() != null,
    )
}

/** Maps a questionnaire answer back to an offered option; null when it does not fit this permission. */
internal fun StudioPermission.decision(answer: Answer): StudioDecision? =
    if (input == null) optionDecision(answer) else inputDecision(answer)

private fun StudioPermission.optionDecision(answer: Answer): StudioDecision? = when (answer) {
    is Answer.Confirmed -> options.getOrNull(if (answer.isConfirmed) 0 else 1)?.let { StudioDecision(it.id, null) }

    is Answer.Selected -> answer.ids.singleOrNull()
        ?.takeIf { id -> options.any { it.id == id } }
        ?.let { StudioDecision(it, null) }

    is Answer.Text, Answer.Skipped -> null
}

private fun StudioPermission.inputDecision(answer: Answer): StudioDecision? {
    val structured = when (answer) {
        Answer.Skipped -> return skipOption()?.let { StudioDecision(it.id, null) }
        is Answer.Selected -> StudioPermissionAnswer.Selected(answer.ids)
        is Answer.Text -> StudioPermissionAnswer.Text(answer.value)
        is Answer.Confirmed -> return null
    }
    return submitOption()?.let { StudioDecision(it.id, structured) }
}

private fun StudioPermission.submitOption() = options.firstOrNull { !it.isSkip }

private fun StudioPermission.skipOption() = options.firstOrNull { it.isSkip }

private fun List<StudioPermissionOption>.toChoices() = map { Choice(it.id, it.title) }

/** Readable user message answering this question when its native request no longer exists. */
internal fun Questionnaire.followUpText(answer: Answer): String {
    val text = when (answer) {
        is Answer.Confirmed -> (question as? Question.Confirm)?.let { if (answer.isConfirmed) it.yes else it.no }
            ?: answer.isConfirmed.toString()

        is Answer.Selected -> {
            val choices = when (val question = question) {
                is Question.SingleChoice -> question.choices
                is Question.MultiChoice -> question.choices
                is Question.Confirm, is Question.FreeText -> emptyList()
            }
            answer.ids.joinToString { id -> choices.firstOrNull { it.id == id }?.title ?: id }
        }

        is Answer.Text -> answer.value

        Answer.Skipped -> "—"
    }
    return "$title\n$text"
}
