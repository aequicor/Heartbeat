package io.aequicor.heartbeat.feature.questionnaire.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class QuestionnaireGenerationTest {
    private val spec = QuestionnaireMachineSpec
    private val question = Questionnaire(
        id = QuestionnaireId("question"),
        source = "source",
        title = "Continue?",
        question = Question.Confirm("Yes", "No"),
    )
    private val answer = Answer.Confirmed(true)

    @Test
    fun `identical replacement rejects the previous automated answer`() {
        val original = ask(QuestionnaireState.Idle)
        val staleAnswer = guardedAnswer(original)
        val replacement = ask(original)

        assertEquals(original.pending, replacement.pending)
        assertNotEquals(original.generations, replacement.generations)
        spec.assertIgnored(replacement, staleAnswer)
        assertAccepted(replacement, guardedAnswer(replacement))
    }

    @Test
    fun `withdraw and reask after idle never reuse the previous generation`() {
        val original = ask(QuestionnaireState.Idle)
        val staleAnswer = guardedAnswer(original)
        val withdrawal = assertNotNull(spec.resolve(original, QuestionnaireIntent.Public.Withdraw(question.id)))
        assertEquals(QuestionnaireState.Idle, withdrawal.to)
        assertEquals(listOf(QuestionnaireOutput.Withdrawn(question.id)), withdrawal.outputs)
        val reopened = ask(withdrawal.to)

        assertNotEquals(original.generations, reopened.generations)
        spec.assertIgnored(reopened, staleAnswer)
        assertAccepted(reopened, guardedAnswer(reopened))
    }

    @Test
    fun `other questions retain their generation through replacement and withdrawal`() {
        val original = ask(QuestionnaireState.Idle)
        val other = question.copy(id = QuestionnaireId("other"))
        val both = ask(original, other)
        val replaced = ask(both, other)
        assertEquals(original.generations[question.id], replaced.generations[question.id])
        val remaining = assertIs<QuestionnaireState.Asking>(
            assertNotNull(spec.resolve(replaced, QuestionnaireIntent.Public.Withdraw(other.id))).to,
        )

        assertEquals(original, remaining)
        assertAccepted(remaining, guardedAnswer(original))
        val reasked = ask(remaining, other)
        spec.assertIgnored(
            reasked,
            QuestionnaireIntent.Public.Answer(
                other.id,
                answer,
                expectedGeneration = replaced.generations.getValue(other.id),
            ),
        )
    }

    @Test
    fun `current generation still validates the answer and accepts it only once`() {
        val current = ask(QuestionnaireState.Idle)
        val context = object : QuestionnaireAnswerContext { }
        val intent = guardedAnswer(current).copy(context = context)
        spec.assertIgnored(current, intent.copy(answer = Answer.Text("invalid")))
        assertAccepted(current, intent)
        spec.assertIgnored(current.copy(submitting = setOf(question.id)), intent)
    }

    @Test
    fun `manual answer without generation still answers an identical replacement`() {
        val original = ask(QuestionnaireState.Idle)
        val replacement = ask(original.copy(submitting = setOf(question.id)))

        spec.assertIgnored(replacement, guardedAnswer(original))
        assertAccepted(replacement, QuestionnaireIntent.Public.Answer(question.id, answer))
    }

    @Test
    fun `missing generation cannot satisfy a guarded answer but preserves manual answers`() {
        val legacy = QuestionnaireState.Asking(listOf(question))
        spec.assertIgnored(legacy, QuestionnaireIntent.Public.Answer(question.id, answer, expectedGeneration = "old"))
        assertAccepted(legacy, QuestionnaireIntent.Public.Answer(question.id, answer))
    }

    @Test
    fun `generation belongs to the ask intent and resolution stays deterministic`() {
        val intent = QuestionnaireIntent.Public.Ask(question)
        val first = assertNotNull(spec.resolve(QuestionnaireState.Idle, intent))
        val replay = assertNotNull(spec.resolve(QuestionnaireState.Idle, intent))
        val copied = assertNotNull(spec.resolve(QuestionnaireState.Idle, intent.copy()))

        assertEquals(first.to, replay.to)
        assertNotEquals(first.to, copied.to)
    }

    @Test
    fun `batch guard rejects a concurrent manual answer before its output is collected`() {
        val other = question.copy(id = QuestionnaireId("other"))
        val both = ask(ask(QuestionnaireState.Idle), other)
        val automatic = guardedAnswer(both).copy(expectedPendingGenerations = both.generations)
        assertAccepted(both, automatic)
        val manual = assertNotNull(spec.resolve(both, QuestionnaireIntent.Public.Answer(other.id, answer)))

        // Resolve directly on the committed state without delivering the manual answer output.
        spec.assertIgnored(manual.to, automatic)
        val withdrawn = assertNotNull(spec.resolve(manual.to, QuestionnaireIntent.Public.Withdraw(other.id)))
        spec.assertIgnored(withdrawn.to, automatic)
        spec.assertIgnored(ask(withdrawn.to, other), automatic)
        spec.assertIgnored(ask(both, other), automatic)
    }

    @Test
    fun `batch guard requires pending ids even if their generations remain`() {
        val other = question.copy(id = QuestionnaireId("other"))
        val both = ask(ask(QuestionnaireState.Idle), other)
        val automatic = guardedAnswer(both).copy(expectedPendingGenerations = both.generations)

        spec.assertIgnored(both.copy(pending = listOf(question)), automatic)
        spec.assertIgnored(both.copy(generations = both.generations - other.id), automatic)
    }

    @Test
    fun `batch guard can exclude an already accepted automatic answer`() {
        val other = question.copy(id = QuestionnaireId("other"))
        val both = ask(ask(QuestionnaireState.Idle), other)
        val first = assertNotNull(spec.resolve(both, QuestionnaireIntent.Public.Answer(other.id, answer)))
        val remaining = guardedAnswer(both).copy(expectedPendingGenerations = both.generations - other.id)
        val accepted = assertNotNull(spec.resolve(first.to, remaining))
        assertEquals(setOf(question.id, other.id), assertIs<QuestionnaireState.Asking>(accepted.to).submitting)
        val withdrawn = assertNotNull(spec.resolve(first.to, QuestionnaireIntent.Public.Withdraw(other.id)))
        assertAccepted(assertIs<QuestionnaireState.Asking>(withdrawn.to), remaining)
    }

    private fun ask(from: QuestionnaireState, next: Questionnaire = question): QuestionnaireState.Asking =
        assertIs<QuestionnaireState.Asking>(
            assertNotNull(spec.resolve(from, QuestionnaireIntent.Public.Ask(next))).to,
        )

    private fun guardedAnswer(state: QuestionnaireState.Asking): QuestionnaireIntent.Public.Answer =
        QuestionnaireIntent.Public.Answer(
            question.id,
            answer,
            expectedGeneration = state.generations.getValue(question.id),
        )

    private fun assertAccepted(state: QuestionnaireState.Asking, intent: QuestionnaireIntent.Public.Answer) {
        spec.assertTransition(
            state,
            intent,
            state.copy(submitting = setOf(question.id)),
            outputs = listOf(QuestionnaireOutput.Answered(question, intent.answer, intent.context)),
        )
    }
}
