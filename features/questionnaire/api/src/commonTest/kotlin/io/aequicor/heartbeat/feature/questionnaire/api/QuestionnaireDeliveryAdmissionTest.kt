package io.aequicor.heartbeat.feature.questionnaire.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuestionnaireDeliveryAdmissionTest {
    private val spec = QuestionnaireMachineSpec
    private val question = Questionnaire(QuestionnaireId("q"), "source", "Continue?", Question.Confirm("Yes", "No"))

    @Test
    fun `refused answer reopens atomically with fresh generation and no output`() {
        val answered = answered()
        val generation = answered.generations.getValue(question.id)
        val reopen = QuestionnaireIntent.Public.ReopenUndelivered(question.id, generation)
        val expected = answered.copy(
            submitting = emptySet(),
            generations = mapOf(question.id to reopen.generation),
            publications = answered.publications.record(question.id, reopen.generation),
        )
        spec.assertTransition(answered, reopen, expected)
        assertNotEquals(generation, reopen.generation)
        spec.assertIgnored(expected, reopen)
        spec.assertIgnored(expected, QuestionnaireIntent.Public.BeginDelivery(question.id, generation))
        spec.assertIgnored(expected, QuestionnaireIntent.Public.AcceptDelivery(question.id, generation))
        val retried = transition(expected, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
        spec.assertIgnored(retried, reopen)
        spec.assertIgnored(retried, QuestionnaireIntent.Public.BeginDelivery(question.id, generation))
        val preparing = transition(retried, QuestionnaireIntent.Public.BeginDelivery(question.id, reopen.generation))
        assertTrue(question.id in preparing.preparing)
    }

    @Test
    fun `reopen cannot touch preparing replacement withdrawn or accepted generations`() {
        val answered = answered()
        val generation = answered.generations.getValue(question.id)
        val reopen = QuestionnaireIntent.Public.ReopenUndelivered(question.id, generation)
        val preparing = transition(answered, QuestionnaireIntent.Public.BeginDelivery(question.id, generation))
        val replacement = transition(answered, QuestionnaireIntent.Public.Ask(question))
        val answeredReplacement = transition(
            replacement,
            QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)),
        )
        val withdrawn = transition(answered, QuestionnaireIntent.Public.Withdraw(question.id))
        val accepted = transition(preparing, QuestionnaireIntent.Public.AcceptDelivery(question.id, generation))
        for (state in listOf(spec.initial, preparing, replacement, answeredReplacement, withdrawn, accepted)) {
            spec.assertIgnored(state, reopen)
        }
        spec.assertIgnored(answered, reopen.copy(expectedGeneration = "stale"))
        spec.assertIgnored(answered, reopen.copy(id = QuestionnaireId("absent")))
        spec.assertIgnored(answered.copy(submitting = emptySet()), reopen)
    }

    @Test
    fun `preparing plus receipts reserve capacity so other acceptance cannot exhaust an admitted slot`() {
        val other = question.copy(id = QuestionnaireId("other"))
        val receipts = (0 until QuestionnaireDeliveryReceipt.LIMIT - 2).associate { index ->
            val older = question.copy(id = QuestionnaireId("older-$index"))
            older.id to QuestionnaireDeliveryReceipt(older, "generation-$index", "receipt-$index")
        }
        val first = answered().copy(deliveryReceipts = receipts)
        val firstGeneration = first.generations.getValue(question.id)
        val firstPreparing = transition(first, QuestionnaireIntent.Public.BeginDelivery(question.id, firstGeneration))
        val secondAsked = transition(firstPreparing, QuestionnaireIntent.Public.Ask(other))
        val secondAnswered = transition(
            secondAsked,
            QuestionnaireIntent.Public.Answer(other.id, Answer.Confirmed(true)),
        )
        val secondGeneration = secondAnswered.generations.getValue(other.id)
        val bothPreparing = transition(
            secondAnswered,
            QuestionnaireIntent.Public.BeginDelivery(other.id, secondGeneration),
        )
        assertEquals(QuestionnaireDeliveryReceipt.LIMIT, bothPreparing.preparing.size + receipts.size)
        val overflow = question.copy(id = QuestionnaireId("overflow"))
        val asked = transition(bothPreparing, QuestionnaireIntent.Public.Ask(overflow))
        val answered = transition(asked, QuestionnaireIntent.Public.Answer(overflow.id, Answer.Confirmed(true)))
        val generation = answered.generations.getValue(overflow.id)
        spec.assertIgnored(answered, QuestionnaireIntent.Public.BeginDelivery(overflow.id, generation))
        val reopened = transition(answered, QuestionnaireIntent.Public.ReopenUndelivered(overflow.id, generation))
        assertEquals(bothPreparing.preparing, reopened.preparing)
        assertEquals(receipts, reopened.deliveryReceipts)
        val secondAccepted = transition(reopened, QuestionnaireIntent.Public.AcceptDelivery(other.id, secondGeneration))
        val firstAccepted = transition(
            secondAccepted,
            QuestionnaireIntent.Public.AcceptDelivery(question.id, firstGeneration),
        )
        assertEquals(QuestionnaireDeliveryReceipt.LIMIT, firstAccepted.deliveryReceipts.size)
        assertTrue(firstAccepted.preparing.isEmpty())
        assertEquals(listOf(overflow), firstAccepted.pending)
        receipts.forEach { (id, receipt) -> assertEquals(receipt, firstAccepted.deliveryReceipts[id]) }
    }

    private fun answered(): QuestionnaireState.Asking {
        val asked = transition(spec.initial, QuestionnaireIntent.Public.Ask(question))
        return transition(asked, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
    }

    private fun transition(state: QuestionnaireState, intent: QuestionnaireIntent): QuestionnaireState.Asking =
        assertIs<QuestionnaireState.Asking>(assertNotNull(spec.resolve(state, intent)).to)
}
