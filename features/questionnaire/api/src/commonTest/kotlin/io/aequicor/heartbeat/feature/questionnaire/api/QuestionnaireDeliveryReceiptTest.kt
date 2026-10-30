package io.aequicor.heartbeat.feature.questionnaire.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestionnaireDeliveryReceiptTest {
    private val spec = QuestionnaireMachineSpec
    private val question = Questionnaire(QuestionnaireId("q"), "source", "Continue?", Question.Confirm("Yes", "No"))

    @Test
    fun `only exact receipt may recover acceptance and retry uses a new generation`() {
        val preparing = prepare()
        val accept = acceptance(preparing)
        val accepted = transition(preparing, accept)
        val fail = failure(accept)
        spec.assertIgnored(accepted, fail.copy(receiptToken = null))
        spec.assertIgnored(accepted, fail.copy(receiptToken = "other"))
        spec.assertIgnored(accepted, fail.copy(expectedGeneration = "other"))
        spec.assertIgnored(accepted, fail.copy(id = QuestionnaireId("other")))
        val retry = transition(accepted, fail)
        assertEquals(listOf(question), retry.pending)
        assertEquals(fail.generation, retry.generations[question.id])
        assertNotEquals(accept.expectedGeneration, fail.generation)
        assertTrue(retry.preparing.isEmpty())
        assertTrue(retry.submitting.isEmpty())
        assertTrue(retry.deliveryReceipts.isEmpty())
        assertEquals(preparing.autoBarrier, retry.autoBarrier)
        spec.assertIgnored(retry, fail)
        spec.assertIgnored(retry, accept)
        spec.assertIgnored(retry, QuestionnaireIntent.Public.CompleteDelivery(question.id, accept.receiptToken))
        val secondPreparing = prepare(retry)
        val second = acceptance(secondPreparing)
        val secondAccepted = transition(secondPreparing, second)
        spec.assertIgnored(secondAccepted, fail)
        assertNotNull(spec.resolve(secondAccepted, failure(second)))
    }

    @Test
    fun `success cleans up only by token and never authorizes generation-only recovery`() {
        val preparing = prepare()
        val accept = acceptance(preparing)
        val accepted = transition(preparing, accept)
        val complete = QuestionnaireIntent.Public.CompleteDelivery(question.id, accept.receiptToken)
        spec.assertIgnored(accepted, complete.copy(receiptToken = "wrong"))
        spec.assertIgnored(accepted, complete.copy(id = QuestionnaireId("wrong")))
        val done = transition(accepted, complete)
        assertTrue(done.deliveryReceipts.isEmpty())
        spec.assertIgnored(done, complete)
        spec.assertIgnored(done, failure(accept))
        spec.assertIgnored(done, QuestionnaireIntent.Public.Ask(question, accept.expectedGeneration, true))
        val next = prepare(done)
        val nextAccept = acceptance(next)
        val nextAccepted = transition(next, nextAccept)
        spec.assertIgnored(nextAccepted, complete)
        assertEquals(nextAccept.receiptToken, nextAccepted.deliveryReceipts.getValue(question.id).token)
    }

    @Test
    fun `accepted replacement and unconditional withdrawal invalidate receipt`() {
        val preparing = prepare()
        val accept = acceptance(preparing)
        val accepted = transition(preparing, accept)
        val invalidators = listOf(
            QuestionnaireIntent.Public.Ask(question),
            QuestionnaireIntent.Public.Withdraw(question.id),
        )
        for (intent in invalidators) {
            val invalidated = transition(accepted, intent)
            assertTrue(invalidated.deliveryReceipts.isEmpty(), intent.toString())
            spec.assertIgnored(invalidated, failure(accept))
            spec.assertIgnored(
                invalidated,
                QuestionnaireIntent.Public.CompleteDelivery(question.id, accept.receiptToken),
            )
            if (intent is QuestionnaireIntent.Public.Ask && intent.expectedGeneration == null) {
                val withdrawn = transition(invalidated, QuestionnaireIntent.Public.Withdraw(question.id))
                spec.assertIgnored(withdrawn, failure(accept))
            } else {
                assertTrue(invalidated.pending.isEmpty())
            }
        }
    }

    @Test
    fun `stale first generation operations preserve second receipt and failure restores second question`() {
        val firstPreparing = prepare()
        val first = acceptance(firstPreparing)
        val firstAccepted = transition(firstPreparing, first)
        val secondQuestion = question.copy(title = "Second publication")
        val secondPreparing = prepare(firstAccepted, secondQuestion)
        val second = acceptance(secondPreparing)
        val accepted = transition(secondPreparing, second)
        val stale = listOf(
            QuestionnaireIntent.Public.Withdraw(question.id, first.expectedGeneration),
            QuestionnaireIntent.Public.Ask(question, first.expectedGeneration),
            QuestionnaireIntent.Public.Ask(question, first.expectedGeneration, true),
            QuestionnaireIntent.Public.Restore(question),
            QuestionnaireIntent.Public.CancelDelivery(question.id, first.receiptToken),
        )
        for (intent in stale) {
            spec.assertIgnored(accepted, intent)
            val fail = failure(second)
            val recovered = transition(accepted, fail)
            assertEquals(listOf(secondQuestion), recovered.pending)
            assertEquals(fail.generation, recovered.generations[question.id])
            assertTrue(recovered.deliveryReceipts.isEmpty())
        }
        assertEquals(second.receiptToken, accepted.deliveryReceipts.getValue(question.id).token)
    }

    @Test
    fun `rejected current generation mutations cannot bypass receipt ownership`() {
        val preparing = prepare()
        val accept = acceptance(preparing)
        val accepted = transition(preparing, accept)
        spec.assertIgnored(accepted, QuestionnaireIntent.Public.Withdraw(question.id, accept.expectedGeneration))
        spec.assertIgnored(accepted, QuestionnaireIntent.Public.Ask(question, accept.expectedGeneration, true))
        val sealed = transition(accepted, QuestionnaireIntent.Public.FinishInitialRestore)
        spec.assertIgnored(sealed, QuestionnaireIntent.Public.Restore(question))
        assertEquals(listOf(question), transition(sealed, failure(accept)).pending)
    }

    @Test
    fun `explicit receipt cancellation requires matching token and leaves other state unchanged`() {
        val preparing = prepare()
        val accept = acceptance(preparing)
        val accepted = transition(preparing, accept)
        val otherQuestion = question.copy(id = QuestionnaireId("other"))
        val otherPreparing = prepare(accepted, otherQuestion)
        val otherAccept = acceptance(otherPreparing, otherQuestion)
        val withOtherReceipt = transition(otherPreparing, otherAccept)
        val cancel = QuestionnaireIntent.Public.CancelDelivery(question.id, accept.receiptToken)
        spec.assertIgnored(spec.initial, cancel)
        spec.assertIgnored(preparing, cancel)
        spec.assertIgnored(withOtherReceipt, cancel.copy(receiptToken = "wrong"))
        spec.assertIgnored(withOtherReceipt, cancel.copy(id = otherQuestion.id))
        val result = assertNotNull(spec.resolve(withOtherReceipt, cancel))
        val cancelled = assertIs<QuestionnaireState.Asking>(result.to)
        assertEquals(
            withOtherReceipt.copy(deliveryReceipts = withOtherReceipt.deliveryReceipts - question.id),
            cancelled,
        )
        assertTrue(result.outputs.isEmpty())
        assertTrue(result.effects.isEmpty())
        spec.assertIgnored(cancelled, cancel)
        spec.assertIgnored(cancelled, failure(accept))
        spec.assertIgnored(cancelled, QuestionnaireIntent.Public.CompleteDelivery(question.id, accept.receiptToken))
        assertEquals(listOf(otherQuestion), transition(cancelled, failure(otherAccept)).pending)
        val replacement = transition(accepted, QuestionnaireIntent.Public.Ask(question))
        spec.assertIgnored(replacement, cancel)
    }

    @Test
    fun `other ids and initial restore seal cannot invalidate receipt`() {
        val preparing = prepare()
        val accept = acceptance(preparing)
        val accepted = transition(preparing, accept)
        val other = question.copy(id = QuestionnaireId("other"))
        val mutations = listOf(
            QuestionnaireIntent.Public.Ask(other),
            QuestionnaireIntent.Public.Restore(other),
            QuestionnaireIntent.Public.Withdraw(other.id),
            QuestionnaireIntent.Public.FinishInitialRestore,
        )
        for (intent in mutations) {
            val changed = transition(accepted, intent)
            assertEquals(accepted.deliveryReceipts, changed.deliveryReceipts)
            val recovered = transition(changed, failure(accept))
            assertTrue(recovered.pending.contains(question))
        }
    }

    @Test
    fun `cancellation cleanup with preallocated token handles both sides of accept commit`() {
        val preparing = prepare()
        // send may commit and then lose its reply to cancellation. The host must keep this object before send.
        val accept = acceptance(preparing)
        val cleanup = failure(accept)
        for (state in listOf(preparing, transition(preparing, accept))) {
            val recovered = transition(state, cleanup)
            assertEquals(cleanup.generation, recovered.generations[question.id])
            assertTrue(recovered.deliveryReceipts.isEmpty())
            spec.assertIgnored(recovered, cleanup)
            spec.assertIgnored(recovered, accept)
        }
    }

    @Test
    fun `cancellation of owner after acceptance can explicitly withdraw absent id`() {
        val preparing = prepare()
        val accept = acceptance(preparing)
        val accepted = transition(preparing, accept)
        val cancelled = transition(accepted, QuestionnaireIntent.Public.Withdraw(question.id))
        assertTrue(cancelled.pending.isEmpty())
        assertTrue(cancelled.deliveryReceipts.isEmpty())
        spec.assertIgnored(cancelled, failure(accept))
    }

    @Test
    fun `dropped withdrawn output retains receipt but process restart has no recovery promise`() {
        val preparing = prepare()
        val accept = acceptance(preparing)
        val result = assertNotNull(spec.resolve(preparing, accept))
        // Commit state but deliberately never dispatch the output: receipt authority is in the state.
        assertEquals(listOf(QuestionnaireOutput.Withdrawn(question.id)), result.outputs)
        assertNotNull(spec.resolve(result.to, failure(accept)))
        assertNull(spec.persistence)
        spec.assertIgnored(spec.initial, failure(accept))
        spec.assertIgnored(spec.initial, QuestionnaireIntent.Public.CompleteDelivery(question.id, accept.receiptToken))
        // Serializability is not runtime persistence or an atomic output outbox.
        val encoded = Json.encodeToString(QuestionnaireState.serializer(), result.to)
        assertEquals(result.to, Json.decodeFromString(QuestionnaireState.serializer(), encoded))
    }

    @Test
    fun `receipt capacity rejects admission without eviction and acknowledgement frees one slot`() {
        var state: QuestionnaireState = QuestionnaireState.Idle
        val accepts = mutableListOf<QuestionnaireIntent.Public.AcceptDelivery>()
        repeat(QuestionnaireDeliveryReceipt.LIMIT) { index ->
            val item = question.copy(id = QuestionnaireId("q-$index"))
            val preparing = prepare(state, item)
            val accept = acceptance(preparing, item)
            state = transition(preparing, accept)
            accepts += accept
        }
        val full = assertIs<QuestionnaireState.Asking>(state)
        assertEquals(QuestionnaireDeliveryReceipt.LIMIT, full.deliveryReceipts.size)
        val asked = transition(full, QuestionnaireIntent.Public.Ask(question))
        val answered = transition(asked, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
        val begin = QuestionnaireIntent.Public.BeginDelivery(question.id, answered.generations.getValue(question.id))
        spec.assertIgnored(answered, begin)
        spec.assertIgnored(answered, acceptance(answered))
        val reopen = QuestionnaireIntent.Public.ReopenUndelivered(question.id, begin.expectedGeneration)
        val cleanup = transition(answered, reopen)
        assertTrue(cleanup.pending.contains(question))
        assertTrue(cleanup.submitting.isEmpty())
        assertEquals(full.deliveryReceipts, cleanup.deliveryReceipts)
        val first = accepts.first()
        val freed = transition(cleanup, QuestionnaireIntent.Public.CompleteDelivery(first.id, first.receiptToken))
        val retry = transition(freed, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
        val preparing = transition(retry, begin.copy(expectedGeneration = reopen.generation))
        val overflow = acceptance(preparing)
        val accepted = transition(preparing, overflow)
        assertEquals(QuestionnaireDeliveryReceipt.LIMIT, accepted.deliveryReceipts.size)
        assertEquals(overflow.receiptToken, accepted.deliveryReceipts.getValue(question.id).token)
        spec.assertIgnored(accepted, failure(first))
        val last = accepts.last()
        assertNotNull(spec.resolve(accepted, failure(last)))
    }

    @Test
    fun `accept resolution is pure and copying the intent cannot acknowledge another acceptance`() {
        val preparing = prepare()
        val accept = acceptance(preparing)
        val accepted = transition(preparing, accept)
        assertEquals(accepted, transition(preparing, accept))
        assertNotEquals(accept.receiptToken, accept.copy().receiptToken)
        spec.assertIgnored(accepted, failure(accept.copy()))
        spec.assertIgnored(accepted, accept)
    }

    private fun prepare(
        state: QuestionnaireState = QuestionnaireState.Idle,
        item: Questionnaire = question,
    ): QuestionnaireState.Asking {
        val asked = transition(state, QuestionnaireIntent.Public.Ask(item))
        val answered = transition(asked, QuestionnaireIntent.Public.Answer(item.id, Answer.Confirmed(true)))
        return transition(
            answered,
            QuestionnaireIntent.Public.BeginDelivery(item.id, answered.generations.getValue(item.id)),
        )
    }

    private fun acceptance(
        preparing: QuestionnaireState.Asking,
        item: Questionnaire = question,
    ): QuestionnaireIntent.Public.AcceptDelivery =
        QuestionnaireIntent.Public.AcceptDelivery(item.id, preparing.generations.getValue(item.id))

    private fun failure(accept: QuestionnaireIntent.Public.AcceptDelivery): QuestionnaireIntent.Public.FailDelivery =
        QuestionnaireIntent.Public.FailDelivery(accept.id, accept.expectedGeneration, accept.receiptToken)

    private fun transition(state: QuestionnaireState, intent: QuestionnaireIntent): QuestionnaireState.Asking =
        assertIs<QuestionnaireState.Asking>(assertNotNull(resolveAfterManualAdmission(state, intent)).to)
}
