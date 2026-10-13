package io.aequicor.heartbeat.feature.questionnaire.screenmodel

import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

/** Changes only editable local drafts; answers and submission progress wait for the owning machine. */
fun QuestionnaireScreenState.predictDraft(intent: QuestionnaireScreenIntent): QuestionnaireScreenState = copy(
    questions = questions.map { question ->
        if (question.id != intent.questionId || question.isSubmitting) question else question.predictDraft(intent)
    }.toImmutableList(),
)

private fun QuestionUi.predictDraft(intent: QuestionnaireScreenIntent): QuestionUi = when (intent) {
    is QuestionnaireScreenIntent.TextChanged -> if (kind is QuestionKindUi.Text) copy(text = intent.text) else this
    is QuestionnaireScreenIntent.ToggleChoice -> toggled(intent.choiceId)
    is QuestionnaireScreenIntent.Confirm, is QuestionnaireScreenIntent.Submit, is QuestionnaireScreenIntent.Skip -> this
}

private fun QuestionUi.toggled(id: String): QuestionUi {
    val choice = kind as? QuestionKindUi.Choice ?: return this
    if (choice.choices.none { it.id == id }) return this
    val next = when {
        !choice.isMultiple -> setOf(id)
        id in selected -> selected - id
        selected.size < choice.max -> selected + id
        else -> selected
    }
    return copy(selected = next.toImmutableSet())
}
