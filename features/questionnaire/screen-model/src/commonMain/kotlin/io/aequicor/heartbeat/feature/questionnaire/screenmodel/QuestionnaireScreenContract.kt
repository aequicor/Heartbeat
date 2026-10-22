@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class)

package io.aequicor.heartbeat.feature.questionnaire.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** One selectable value. */
@Serializable
@Immutable
data class ChoiceUi(val id: String, val title: String)

/** How a question is answered on screen. */
@Serializable
@Immutable
sealed interface QuestionKindUi {
    /** Two buttons with the source's labels. */
    @Serializable
    data class Confirm(val yes: String, val no: String) : QuestionKindUi

    /** Chips; [isMultiple] allows [min]..[max] selections, otherwise exactly one. */
    @Serializable
    data class Choice(
        val choices: ImmutableList<ChoiceUi>,
        val isMultiple: Boolean,
        val min: Int = 1,
        val max: Int = 1,
    ) : QuestionKindUi

    /** A text field. */
    @Serializable
    data class Text(val placeholder: String?, val isMultiline: Boolean) : QuestionKindUi
}

/** A pending question with the user's unsent draft. */
@Serializable
@Immutable
data class QuestionUi(
    val id: String,
    val title: String,
    val description: String?,
    val kind: QuestionKindUi,
    val isSkippable: Boolean,
    val isSubmitting: Boolean,
    val selected: ImmutableSet<String> = persistentSetOf(),
    val text: String = "",
    val isFreeTextAllowed: Boolean = false,
) {
    /** Whether the draft is a complete answer. */
    val isAnswerReady: Boolean
        get() = !isSubmitting && when (kind) {
            is QuestionKindUi.Confirm -> isFreeTextAllowed && text.isNotBlank()

            is QuestionKindUi.Choice ->
                selected.size in kind.min..kind.max ||
                    (isFreeTextAllowed && selected.isEmpty() && text.isNotBlank())

            is QuestionKindUi.Text -> text.isNotBlank()
        }
}

/** Pending questions of the shown source, in asking order. */
@Serializable
@Immutable
data class QuestionnaireScreenState(
    val questions: ImmutableList<QuestionUi> = persistentListOf(),
    /** Host-local commit revision; the remote handle sends it in its envelope. */
    @Transient val revision: Long = 0,
    /** Stable identity of the visible page; drafts remain attached to all pending questions. */
    val activeQuestionId: String? = null,
) : MVIState

/** User input on the questionnaire screen. */
@Serializable
sealed interface QuestionnaireScreenIntent : MVIIntent {
    /** The question this input belongs to. */
    val questionId: String

    /** Opens one pending question without submitting its draft. */
    @Serializable
    data class ShowQuestion(override val questionId: String) : QuestionnaireScreenIntent

    /** Selects or unselects [choiceId]; a single choice replaces the selection. */
    @Serializable
    data class ToggleChoice(override val questionId: String, val choiceId: String) : QuestionnaireScreenIntent

    /** Replaces the draft text. */
    @Serializable
    data class TextChanged(override val questionId: String, val text: String) : QuestionnaireScreenIntent

    /** Answers a yes/no question. */
    @Serializable
    data class Confirm(override val questionId: String, val isConfirmed: Boolean) : QuestionnaireScreenIntent

    /** Sends the draft as the answer. */
    @Serializable
    data class Submit(override val questionId: String) : QuestionnaireScreenIntent

    /** Declines to answer. */
    @Serializable
    data class Skip(override val questionId: String) : QuestionnaireScreenIntent
}

/** The screen has no one-off actions. */
sealed interface QuestionnaireScreenAction : MVIAction
