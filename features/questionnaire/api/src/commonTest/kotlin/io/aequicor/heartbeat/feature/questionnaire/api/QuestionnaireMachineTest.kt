package io.aequicor.heartbeat.feature.questionnaire.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
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
    fun `accepted answers relay trusted context without storing it in queue state`() {
        val context = object : QuestionnaireAnswerContext { }
        assertAfterManualAdmission(
            asking,
            QuestionnaireIntent.Public.Answer(pick.id, Answer.Selected(listOf("a")), context),
            asking.copy(submitting = setOf(pick.id), autoBarrier = QuestionnaireAutoBarrier(setOf(pick.source))),
            outputs = listOf(QuestionnaireOutput.Answered(pick, Answer.Selected(listOf("a")), context)),
        )
    }

    @Test
    fun `ask from idle starts asking and appends or replaces later questions`() {
        val first = QuestionnaireIntent.Public.Ask(pick)
        assertAfterManualAdmission(
            QuestionnaireState.Idle,
            first,
            asking.copy(
                generations = mapOf(pick.id to first.generation),
                publications = QuestionnairePublicationHistory(mapOf(pick.id to first.generation)),
            ),
        )
        val second = QuestionnaireIntent.Public.Ask(confirm)
        assertAfterManualAdmission(
            asking,
            second,
            QuestionnaireState.Asking(
                listOf(pick, confirm),
                generations = mapOf(confirm.id to second.generation),
                publications = QuestionnairePublicationHistory(mapOf(confirm.id to second.generation)),
            ),
        )
        val renamed = pick.copy(title = "Pick again")
        val replacement = QuestionnaireIntent.Public.Ask(renamed)
        assertAfterManualAdmission(
            asking.copy(submitting = setOf(pick.id)),
            replacement,
            QuestionnaireState.Asking(
                listOf(renamed),
                generations = mapOf(pick.id to replacement.generation),
                publications = QuestionnairePublicationHistory(mapOf(pick.id to replacement.generation)),
            ),
        )
    }

    @Test
    fun `valid answer is submitted once and reported to the source`() {
        val answer = Answer.Selected(listOf("b"))
        val submitting = asking.copy(
            submitting = setOf(pick.id),
            autoBarrier = QuestionnaireAutoBarrier(setOf(pick.source)),
        )
        assertAfterManualAdmission(
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
        assertAfterManualAdmission(
            asking,
            QuestionnaireIntent.Public.Answer(pick.id, Answer.Skipped),
            asking.copy(submitting = setOf(pick.id)),
            outputs = listOf(QuestionnaireOutput.Answered(pick, Answer.Skipped)),
        )
    }

    @Test
    fun `withdraw removes the question and the last one returns to idle`() {
        val both = QuestionnaireState.Asking(listOf(pick, confirm), setOf(pick.id))
        assertAfterManualAdmission(
            both,
            QuestionnaireIntent.Public.Withdraw(pick.id),
            QuestionnaireState.Asking(
                listOf(confirm),
                publications = QuestionnairePublicationHistory(mapOf(pick.id to null)),
            ),
            outputs = listOf(QuestionnaireOutput.Withdrawn(pick.id)),
        )
        assertAfterManualAdmission(
            asking,
            QuestionnaireIntent.Public.Withdraw(pick.id),
            QuestionnaireState.Asking(
                emptyList(),
                publications = QuestionnairePublicationHistory(mapOf(pick.id to null)),
            ),
            outputs = listOf(QuestionnaireOutput.Withdrawn(pick.id)),
        )
        spec.assertIgnored(asking, QuestionnaireIntent.Public.Withdraw(QuestionnaireId("x"), "stale"))
        spec.assertIgnored(QuestionnaireState.Idle, QuestionnaireIntent.Public.Withdraw(pick.id, "stale"))
    }

    @Test
    fun `free text is opt in and cannot grant a strict permission`() {
        val alternative = Answer.Text("Use another database")
        val comment = Answer.Selected(listOf("a"), "For the prototype")
        val open = pick.copy(isFreeTextAllowed = true)
        val openState = QuestionnaireState.Asking(listOf(open))
        for (answer in listOf(alternative, comment)) {
            assertAfterManualAdmission(
                openState,
                QuestionnaireIntent.Public.Answer(open.id, answer),
                openState.copy(submitting = setOf(open.id), autoBarrier = QuestionnaireAutoBarrier(setOf(open.source))),
                outputs = listOf(QuestionnaireOutput.Answered(open, answer)),
            )
            spec.assertIgnored(asking, QuestionnaireIntent.Public.Answer(pick.id, answer))
        }
        assertFalse(open.accepts(Answer.Text("  ")))
        assertFalse(open.accepts(Answer.Selected(listOf("unknown"), "Comment")))
        assertFalse(confirm.accepts(Answer.Text("yes")))
        assertFalse(confirm.accepts(Answer.Confirmed(true, "Only if tests pass")))
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
}
