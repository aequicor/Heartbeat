package io.aequicor.heartbeat.feature.questionnaire.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestionnaireDeliveryTest {
    private val spec = QuestionnaireMachineSpec
    private val question = Questionnaire(QuestionnaireId("q"), "s", "Continue?", Question.Confirm("Yes", "No"))
    private val answer = Answer.Confirmed(true)

    @Test
    fun `gate requires an answered current publication and is accepted only once`() {
        val asked = ask()
        val generation = asked.generations.getValue(question.id)
        val gate = QuestionnaireIntent.Public.BeginDelivery(question.id, generation)
        spec.assertIgnored(QuestionnaireState.Idle, gate)
        spec.assertIgnored(asked, gate)
        val answered = answer(asked)
        spec.assertIgnored(answered, gate.copy(expectedGeneration = "stale"))
        spec.assertIgnored(answered, gate.copy(id = QuestionnaireId("missing")))
        spec.assertTransition(answered, gate, answered.copy(preparing = setOf(question.id)))
        val preparing = transition(answered, gate)
        spec.assertIgnored(preparing, gate)
    }

    @Test
    fun `completions without a successful gate are ignored`() {
        val asked = ask()
        val generation = asked.generations.getValue(question.id)
        for (state in listOf(QuestionnaireState.Idle, asked, answer(asked))) {
            spec.assertIgnored(state, QuestionnaireIntent.Public.AcceptDelivery(question.id, generation))
            spec.assertIgnored(state, QuestionnaireIntent.Public.FailDelivery(question.id, generation))
        }
    }

    @Test
    fun `ask and withdraw before preparation invalidate an emitted answer`() {
        val answered = answer(ask())
        val generation = answered.generations.getValue(question.id)
        val gate = QuestionnaireIntent.Public.BeginDelivery(question.id, generation)
        val withdrawn = transition(answered, QuestionnaireIntent.Public.Withdraw(question.id))
        for (state in listOf(ask(answered), withdrawn, ask(withdrawn))) {
            spec.assertIgnored(state, gate)
            spec.assertIgnored(state, QuestionnaireIntent.Public.AcceptDelivery(question.id, generation))
            spec.assertIgnored(state, QuestionnaireIntent.Public.FailDelivery(question.id, generation))
        }
        assertTrue(withdrawn.pending.isEmpty())
        assertTrue(withdrawn.preparing.isEmpty())
    }

    @Test
    fun `late success or failure after preparation cannot mutate a replacement or resurrect its withdrawal`() {
        val preparing = begin(answer(ask()))
        val generation = preparing.generations.getValue(question.id)
        val replacement = ask(preparing)
        val withdrawn = transition(replacement, QuestionnaireIntent.Public.Withdraw(question.id))
        val replacementPreparing = begin(answer(replacement))
        for (state in listOf(replacement, withdrawn, replacementPreparing)) {
            spec.assertIgnored(state, QuestionnaireIntent.Public.AcceptDelivery(question.id, generation))
            spec.assertIgnored(state, QuestionnaireIntent.Public.FailDelivery(question.id, generation))
        }
        assertTrue(replacement.preparing.isEmpty())
        assertEquals(setOf(question.id), replacementPreparing.preparing)
    }

    @Test
    fun `accepted delivery empties the queue and retains history`() {
        val preparing = begin(answer(ask()))
        val generation = preparing.generations.getValue(question.id)
        val accept = QuestionnaireIntent.Public.AcceptDelivery(question.id, generation)
        val result = assertNotNull(spec.resolve(preparing, accept))
        val idle = assertIs<QuestionnaireState.Asking>(result.to)
        assertTrue(idle.pending.isEmpty())
        assertTrue(idle.submitting.isEmpty())
        assertTrue(idle.generations.isEmpty())
        assertTrue(idle.preparing.isEmpty())
        assertNull(idle.publications.latest[question.id])
        assertEquals(accept.receiptToken, idle.deliveryReceipts.getValue(question.id).token)
        assertEquals(listOf(QuestionnaireOutput.Withdrawn(question.id)), result.outputs)
        spec.assertIgnored(idle, accept)
        spec.assertIgnored(idle, QuestionnaireIntent.Public.FailDelivery(question.id, generation))
        spec.assertIgnored(idle, QuestionnaireIntent.Public.Restore(question))
        assertEquals(accept.receiptToken, idle.deliveryReceipts.getValue(question.id).token)
    }

    @Test
    fun `failure reopens as a new publication and old completion cannot affect a new attempt`() {
        val preparing = begin(answer(ask()))
        val oldGeneration = preparing.generations.getValue(question.id)
        val fail = QuestionnaireIntent.Public.FailDelivery(question.id, oldGeneration)
        val reopened = transition(preparing, fail)
        assertEquals(preparing.pending, reopened.pending)
        assertTrue(reopened.submitting.isEmpty())
        assertTrue(reopened.preparing.isEmpty())
        assertEquals(fail.generation, reopened.generations[question.id])
        assertNotEquals(oldGeneration, fail.generation)
        val retry = begin(answer(reopened))
        for (state in listOf(reopened, retry)) {
            spec.assertIgnored(state, fail)
            spec.assertIgnored(state, QuestionnaireIntent.Public.AcceptDelivery(question.id, oldGeneration))
            spec.assertIgnored(state, QuestionnaireIntent.Public.BeginDelivery(question.id, oldGeneration))
        }
        val done = transition(retry, QuestionnaireIntent.Public.AcceptDelivery(question.id, fail.generation))
        assertTrue(done.pending.isEmpty())
    }

    @Test
    fun `withdraw after preparation prevents either completion from reopening it`() {
        val preparing = begin(answer(ask()))
        val generation = preparing.generations.getValue(question.id)
        val withdrawn = transition(preparing, QuestionnaireIntent.Public.Withdraw(question.id, generation))
        spec.assertIgnored(withdrawn, QuestionnaireIntent.Public.AcceptDelivery(question.id, generation))
        spec.assertIgnored(withdrawn, QuestionnaireIntent.Public.FailDelivery(question.id, generation))
        assertTrue(withdrawn.preparing.isEmpty())
    }

    @Test
    fun `gates and completions are independent for different question ids`() {
        val preparing = begin(answer(ask()))
        val other = question.copy(id = QuestionnaireId("other"))
        val both = transition(preparing, QuestionnaireIntent.Public.Ask(other))
        val answered = transition(both, QuestionnaireIntent.Public.Answer(other.id, answer))
        val otherGeneration = answered.generations.getValue(other.id)
        val bothPreparing = transition(answered, QuestionnaireIntent.Public.BeginDelivery(other.id, otherGeneration))
        assertEquals(setOf(question.id, other.id), bothPreparing.preparing)
        val removed = transition(bothPreparing, QuestionnaireIntent.Public.AcceptDelivery(other.id, otherGeneration))
        assertEquals(
            preparing.copy(publications = removed.publications, deliveryReceipts = removed.deliveryReceipts),
            removed,
        )
        val failed = transition(bothPreparing, QuestionnaireIntent.Public.FailDelivery(other.id, otherGeneration))
        assertEquals(preparing.generations[question.id], failed.generations[question.id])
        assertEquals(setOf(question.id), failed.preparing)
        assertEquals(setOf(question.id), failed.submitting)
    }

    @Test
    fun `resolving a failure twice is pure but a new failure intent allocates a new generation`() {
        val preparing = begin(answer(ask()))
        val fail = QuestionnaireIntent.Public.FailDelivery(question.id, preparing.generations.getValue(question.id))
        assertEquals(transition(preparing, fail), transition(preparing, fail))
        assertNotEquals(transition(preparing, fail), transition(preparing, fail.copy()))
    }

    @Test
    fun `legacy manual answers remain supported but cannot claim delivery without a generation`() {
        val legacy = QuestionnaireState.Asking(listOf(question))
        val answered = answer(legacy)
        assertEquals(setOf(question.id), answered.submitting)
        spec.assertIgnored(answered, QuestionnaireIntent.Public.BeginDelivery(question.id, "unknown"))
    }

    private fun ask(state: QuestionnaireState = QuestionnaireState.Idle): QuestionnaireState.Asking =
        transition(state, QuestionnaireIntent.Public.Ask(question))

    private fun answer(state: QuestionnaireState.Asking): QuestionnaireState.Asking =
        transition(state, QuestionnaireIntent.Public.Answer(question.id, answer))

    private fun begin(state: QuestionnaireState.Asking): QuestionnaireState.Asking = transition(
        state,
        QuestionnaireIntent.Public.BeginDelivery(question.id, state.generations.getValue(question.id)),
    )

    private fun transition(state: QuestionnaireState, intent: QuestionnaireIntent): QuestionnaireState.Asking =
        assertIs<QuestionnaireState.Asking>(assertNotNull(spec.resolve(state, intent)).to)
}
