package io.aequicor.heartbeat.feature.questionnaire.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestionnaireMachineTest {
    private val spec = QuestionnaireMachineSpec
    private val pick = Questionnaire(
        QuestionnaireId("pick"),
        "s1",
        "Pick one",
        Question.SingleChoice(listOf(Choice("a", "A"), Choice("b", "B"))),
    )
    private val confirm = Questionnaire(
        QuestionnaireId("go"),
        "s1",
        "Go?",
        Question.Confirm("Yes", "No"),
        isSkippable = false,
    )
    private val asking = QuestionnaireState.Asking(listOf(pick))

    @Test
    fun `ask from idle starts asking and appends or replaces later questions`() {
        spec.assertTransition(QuestionnaireState.Idle, QuestionnaireIntent.Public.Ask(pick), asking)
        spec.assertTransition(
            asking,
            QuestionnaireIntent.Public.Ask(confirm),
            QuestionnaireState.Asking(listOf(pick, confirm)),
        )
        val renamed = pick.copy(title = "Pick again")
        spec.assertTransition(
            asking.copy(submitting = setOf(pick.id)),
            QuestionnaireIntent.Public.Ask(renamed),
            QuestionnaireState.Asking(listOf(renamed)),
        )
    }

    @Test
    fun `valid answer is submitted once and reported to the source`() {
        val answer = Answer.Selected(listOf("b"))
        val submitting = asking.copy(submitting = setOf(pick.id))
        spec.assertTransition(
            asking,
            QuestionnaireIntent.Public.Answer(pick.id, answer),
            submitting,
            outputs = listOf(QuestionnaireOutput.Answered(pick, answer)),
        )
        spec.assertIgnored(submitting, QuestionnaireIntent.Public.Answer(pick.id, answer))
    }

    @Test
    fun `invalid foreign or unskippable answers are ignored`() {
        spec.assertIgnored(asking, QuestionnaireIntent.Public.Answer(pick.id, Answer.Selected(listOf("z"))))
        spec.assertIgnored(asking, QuestionnaireIntent.Public.Answer(pick.id, Answer.Text("b")))
        spec.assertIgnored(asking, QuestionnaireIntent.Public.Answer(QuestionnaireId("x"), Answer.Skipped))
        spec.assertIgnored(QuestionnaireState.Idle, QuestionnaireIntent.Public.Answer(pick.id, Answer.Skipped))
        val strict = QuestionnaireState.Asking(listOf(confirm))
        spec.assertIgnored(strict, QuestionnaireIntent.Public.Answer(confirm.id, Answer.Skipped))
        spec.assertTransition(
            asking,
            QuestionnaireIntent.Public.Answer(pick.id, Answer.Skipped),
            asking.copy(submitting = setOf(pick.id)),
            outputs = listOf(QuestionnaireOutput.Answered(pick, Answer.Skipped)),
        )
    }

    @Test
    fun `withdraw removes the question and the last one returns to idle`() {
        val both = QuestionnaireState.Asking(listOf(pick, confirm), setOf(pick.id))
        spec.assertTransition(
            both,
            QuestionnaireIntent.Public.Withdraw(pick.id),
            QuestionnaireState.Asking(listOf(confirm)),
            outputs = listOf(QuestionnaireOutput.Withdrawn(pick.id)),
        )
        spec.assertTransition(
            asking,
            QuestionnaireIntent.Public.Withdraw(pick.id),
            QuestionnaireState.Idle,
            outputs = listOf(QuestionnaireOutput.Withdrawn(pick.id)),
        )
        spec.assertIgnored(asking, QuestionnaireIntent.Public.Withdraw(QuestionnaireId("x")))
        spec.assertIgnored(QuestionnaireState.Idle, QuestionnaireIntent.Public.Withdraw(pick.id))
    }

    @Test
    fun `answers are validated against the question kind`() {
        val multi = Question.MultiChoice(listOf(Choice("a", "A"), Choice("b", "B"), Choice("c", "C")), 1, 2)
        assertTrue(Answer.Selected(listOf("a", "c")).validFor(multi))
        assertFalse(Answer.Selected(emptyList()).validFor(multi))
        assertFalse(Answer.Selected(listOf("a", "a")).validFor(multi))
        assertTrue(Answer.Confirmed(false).validFor(Question.Confirm("y", "n")))
        assertTrue(Answer.Text("").validFor(Question.FreeText()))
        assertFalse(Answer.Skipped.validFor(Question.FreeText()))
    }

    @Test
    fun `restoration keeps pending questions and reopens submitted ones`() {
        val restored = spec.restore(QuestionnaireState.Asking(listOf(pick, confirm), setOf(pick.id)))
        kotlin.test.assertEquals(QuestionnaireState.Asking(listOf(pick, confirm)), restored.state)
        assertTrue(restored.effects.isEmpty())
        kotlin.test.assertEquals(QuestionnaireState.Idle, spec.restore(QuestionnaireState.Idle).state)
    }
}
