package io.aequicor.heartbeat.feature.questionnaire.api

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Identity of one question, unique within the profile (sources prefix it with their own identity). */
@Serializable
@JvmInline
public value class QuestionnaireId(public val value: String)

/**
 * Prefix of question ids owned by a live caller, e.g. a hosted ask-user tool: it collects the answers itself
 * while its turn runs. The journal does not persist such questions (their owner dies with the turn), and answer
 * bridges do not follow them up.
 */
public const val LIVE_QUESTION_ID_PREFIX: String = "live/"

/**
 * A question an agent (or any other [source]) asks the user.
 * [source] groups questions for one screen, e.g. the chat session that asked them.
 * [isSkippable] allows [Answer.Skipped]; otherwise only a valid answer resolves it.
 */
@Serializable
public data class Questionnaire(
    val id: QuestionnaireId,
    val source: String,
    val title: String,
    val question: Question,
    val description: String? = null,
    val isSkippable: Boolean = true,
)

/** Selectable value of a choice question. */
@Serializable
public data class Choice(val id: String, val title: String)

/** What kind of answer the question expects. */
@Serializable
public sealed interface Question {
    /** Yes/no with the source's own labels. */
    @Serializable
    public data class Confirm(val yes: String, val no: String) : Question

    /** Exactly one of [choices]. */
    @Serializable
    public data class SingleChoice(val choices: List<Choice>) : Question {
        init {
            require(choices.isNotEmpty())
            require(choices.map { it.id }.distinct().size == choices.size)
        }
    }

    /** Between [min] and [max] of [choices]. */
    @Serializable
    public data class MultiChoice(val choices: List<Choice>, val min: Int = 0, val max: Int = choices.size) : Question {
        init {
            require(choices.isNotEmpty())
            require(choices.map { it.id }.distinct().size == choices.size)
            require(min in 0..max && max <= choices.size)
        }
    }

    /** Free-form text; [isMultiline] asks for a larger editor. */
    @Serializable
    public data class FreeText(val placeholder: String? = null, val isMultiline: Boolean = false) : Question
}

/** The user's answer. */
@Serializable
public sealed interface Answer {
    /** Answer to [Question.Confirm]. */
    @Serializable
    public data class Confirmed(val isConfirmed: Boolean) : Answer

    /** Chosen [Choice.id]s of a choice question. */
    @Serializable
    public data class Selected(val ids: List<String>) : Answer

    /** Entered text of [Question.FreeText]. */
    @Serializable
    public data class Text(val value: String) : Answer

    /** The user declined to answer. */
    @Serializable
    public data object Skipped : Answer
}

/** True when this answer has the kind and values [question] expects; [Answer.Skipped] never fits a question. */
public fun Answer.validFor(question: Question): Boolean = when (question) {
    is Question.Confirm -> this is Answer.Confirmed
    is Question.SingleChoice -> this is Answer.Selected && ids.size == 1 && offered(question.choices)
    is Question.MultiChoice -> this is Answer.Selected && withinBounds(question) && offered(question.choices)
    is Question.FreeText -> this is Answer.Text
}

/** True when [answer] resolves this questionnaire: a valid answer, or a skip when skipping is allowed. */
public fun Questionnaire.accepts(answer: Answer): Boolean =
    if (answer == Answer.Skipped) isSkippable else answer.validFor(question)

private fun Answer.Selected.withinBounds(question: Question.MultiChoice): Boolean =
    ids.size in question.min..question.max && ids.distinct().size == ids.size

private fun Answer.Selected.offered(choices: List<Choice>): Boolean = ids.all { id -> choices.any { it.id == id } }
