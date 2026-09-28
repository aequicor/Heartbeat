package io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** One selectable value. */
@Immutable
internal data class ChoiceUi(val id: String, val title: String)

/** How a question is answered on screen. */
@Immutable
internal sealed interface QuestionKindUi {
    /** Two buttons with the source's labels. */
    data class Confirm(val yes: String, val no: String) : QuestionKindUi

    /** Chips; [isMultiple] allows [min]..[max] selections, otherwise exactly one. */
    data class Choice(
        val choices: ImmutableList<ChoiceUi>,
        val isMultiple: Boolean,
        val min: Int = 1,
        val max: Int = 1,
    ) : QuestionKindUi

    /** A text field. */
    data class Text(val placeholder: String?, val isMultiline: Boolean) : QuestionKindUi
}

/** A pending question with the user's unsent draft. */
@Immutable
internal data class QuestionUi(
    val id: String,
    val title: String,
    val description: String?,
    val kind: QuestionKindUi,
    val isSkippable: Boolean,
    val isSubmitting: Boolean,
    val selected: ImmutableSet<String> = persistentSetOf(),
    val text: String = "",
) {
    /** Whether the draft is a complete answer. */
    val isAnswerReady: Boolean
        get() = !isSubmitting && when (kind) {
            is QuestionKindUi.Confirm -> false
            is QuestionKindUi.Choice -> selected.size in kind.min..kind.max
            is QuestionKindUi.Text -> text.isNotBlank()
        }
}

/** Pending questions of the shown source, in asking order. */
@Immutable
internal data class QuestionnaireScreenState(val questions: ImmutableList<QuestionUi> = persistentListOf()) : MVIState

/** User input on the questionnaire screen. */
internal sealed interface QuestionnaireScreenIntent : MVIIntent {
    /** The question this input belongs to. */
    val questionId: String

    /** Selects or unselects [choiceId]; a single choice replaces the selection. */
    data class ToggleChoice(override val questionId: String, val choiceId: String) : QuestionnaireScreenIntent

    /** Replaces the draft text. */
    data class TextChanged(override val questionId: String, val text: String) : QuestionnaireScreenIntent

    /** Answers a yes/no question. */
    data class Confirm(override val questionId: String, val isConfirmed: Boolean) : QuestionnaireScreenIntent

    /** Sends the draft as the answer. */
    data class Submit(override val questionId: String) : QuestionnaireScreenIntent

    /** Declines to answer. */
    data class Skip(override val questionId: String) : QuestionnaireScreenIntent
}

/** The screen has no one-off actions. */
internal sealed interface QuestionnaireScreenAction : MVIAction
