package io.aequicor.heartbeat.feature.questionnaire.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuestionnaireManualAdmissionTest {
    private val question = Questionnaire(
        QuestionnaireId("manual"),
        "chat",
        "New restriction",
        Question.FreeText(),
    )
    private val spec = QuestionnaireMachineSpec

    @Test
    fun `manual preparation has no final acceptance or output and blocks automation`() {
        val initial = ask(QuestionnaireState.Idle)
        val resolution = assertNotNull(spec.resolve(initial, answer()))
        val preparing = assertIs<QuestionnaireState.Asking>(resolution.to)
        assertTrue(resolution.outputs.isEmpty())
        assertTrue(preparing.submitting.isEmpty())
        assertEquals(listOf(question), preparing.pending)
        assertTrue(preparing.autoBarrier.isBlocked(question.source))
        assertEquals(1, preparing.manualPreparations.size)
        assertIs<QuestionnaireEffect.PrepareManual>(resolution.effects.single())
        spec.assertIgnored(preparing, answer())
        spec.assertIgnored(
            preparing,
            answer().copy(
                origin = QuestionnaireAnswerOrigin.Auto,
                expectedGeneration = initial.generations.getValue(question.id),
            ),
        )
    }

    @Test
    fun `only durable completion publishes the captured answer exactly once`() {
        val reservation = assertNotNull(spec.resolve(ask(QuestionnaireState.Idle), answer()))
        val effect = assertIs<QuestionnaireEffect.PrepareManual>(reservation.effects.single())
        val ready = QuestionnaireIntent.Internal.ManualPrepared(effect)
        val accepted = assertNotNull(spec.resolve(reservation.to, ready))
        val state = assertIs<QuestionnaireState.Asking>(accepted.to)
        assertEquals(setOf(question.id), state.submitting)
        assertTrue(state.manualPreparations.isEmpty())
        val output = assertIs<QuestionnaireOutput.Answered>(accepted.outputs.single())
        assertEquals(effect.answer.answer, output.answer)
        assertEquals(effect.generation, output.generation)
        spec.assertIgnored(state, ready)
    }

    @Test
    fun `replacement withdrawal and failed attempt reject late completion`() {
        val reservation = assertNotNull(spec.resolve(ask(QuestionnaireState.Idle), answer()))
        val effect = assertIs<QuestionnaireEffect.PrepareManual>(reservation.effects.single())
        val ready = QuestionnaireIntent.Internal.ManualPrepared(effect)
        spec.assertIgnored(ask(reservation.to), ready)
        val withdrawn = assertNotNull(spec.resolve(reservation.to, QuestionnaireIntent.Public.Withdraw(question.id)))
        spec.assertIgnored(withdrawn.to, ready)
        val failed = assertNotNull(
            spec.resolve(
                reservation.to,
                QuestionnaireIntent.Internal.ManualPreparationFailed(question.id, effect.answer.admissionToken),
            ),
        )
        val state = assertIs<QuestionnaireState.Asking>(failed.to)
        assertTrue(state.submitting.isEmpty())
        assertTrue(state.manualPreparations.isEmpty())
        assertTrue(state.autoBarrier.isBlocked(question.source))
        assertTrue(failed.outputs.isEmpty())
        val retry = assertNotNull(spec.resolve(state, answer()))
        spec.assertIgnored(retry.to, ready)
    }

    @Test
    fun `durable preparation failures release only their own attempt`() {
        val first = assertNotNull(spec.resolve(ask(QuestionnaireState.Idle), answer()))
        val effect = assertIs<QuestionnaireEffect.PrepareManual>(first.effects.single())
        val failure = assertIs<QuestionnaireIntent.Internal.ManualPreparationFailed>(
            spec.onEffectFailure(effect, IllegalStateException("storage unavailable")),
        )
        assertEquals(effect.answer.admissionToken, failure.token)
        val released = assertNotNull(spec.resolve(first.to, failure))
        val retry = assertNotNull(spec.resolve(released.to, answer()))
        spec.assertIgnored(retry.to, failure)
        spec.assertIgnored(retry.to, QuestionnaireIntent.Internal.ManualPrepared(effect))
    }

    @Test
    fun `skipped answer needs no durable revocation`() {
        val optional = question.copy(isSkippable = true)
        val initial = assertNotNull(spec.resolve(QuestionnaireState.Idle, QuestionnaireIntent.Public.Ask(optional))).to
        val result = assertNotNull(spec.resolve(initial, answer().copy(answer = Answer.Skipped)))
        assertTrue(result.effects.isEmpty())
        assertEquals(1, result.outputs.size)
        assertFalse(assertIs<QuestionnaireState.Asking>(result.to).autoBarrier.isBlocked(question.source))
    }

    private fun answer() = QuestionnaireIntent.Public.Answer(question.id, Answer.Text("Never delete files"))

    private fun ask(state: QuestionnaireState): QuestionnaireState.Asking = assertIs(
        assertNotNull(spec.resolve(state, QuestionnaireIntent.Public.Ask(question))).to,
    )
}
