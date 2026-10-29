package io.aequicor.heartbeat.feature.questionnaire.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestionnaireAutoBarrierTest {
    private val spec = QuestionnaireMachineSpec
    private val question = Questionnaire(QuestionnaireId("q"), "source", "Continue?", Question.Confirm("Yes", "No"))

    @Test
    fun `manual answer blocks another batch atomically before output collection`() {
        val first = ask()
        val other = question.copy(id = QuestionnaireId("other-batch"))
        val both = ask(first, other)
        val automatic = auto(
            both,
            other,
        ).copy(expectedPendingGenerations = mapOf(other.id to both.generations.getValue(other.id)))
        assertNotNull(spec.resolve(both, automatic))
        val manual = assertNotNull(
            spec.resolve(both, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true))),
        )
        // No output delivery or host context callback has happened; the separate batch snapshot is still current.
        val answered = assertIs<QuestionnaireState.Asking>(manual.to)
        assertEquals(both.generations[other.id], answered.generations[other.id])
        assertEquals(setOf(question.source), answered.autoBarrier.sources)
        spec.assertIgnored(answered, automatic)
        val withdrawn = transition(answered, QuestionnaireIntent.Public.Withdraw(question.id))
        spec.assertIgnored(withdrawn, automatic)
    }

    @Test
    fun `auto accepted before manual is not revoked but later auto is rejected`() {
        val other = question.copy(id = QuestionnaireId("manual"))
        val third = question.copy(id = QuestionnaireId("third"))
        val all = ask(ask(ask(), other), third)
        val automated = transition(all, auto(all))
        assertFalse(automated.autoBarrier.isBlocked(question.source))
        val manual = transition(automated, QuestionnaireIntent.Public.Answer(other.id, Answer.Confirmed(false)))
        assertTrue(question.id in manual.submitting)
        spec.assertIgnored(manual, auto(manual, third))
    }

    @Test
    fun `manual skip does not block the source but invalid manual answers do nothing`() {
        val other = question.copy(id = QuestionnaireId("other"))
        val both = ask(ask(), other)
        spec.assertIgnored(both, QuestionnaireIntent.Public.Answer(question.id, Answer.Text("invalid")))
        val skipped = transition(both, QuestionnaireIntent.Public.Answer(question.id, Answer.Skipped))
        assertEquals(QuestionnaireAutoBarrier(), skipped.autoBarrier)
        val automated = transition(skipped, auto(skipped, other))
        assertEquals(QuestionnaireAutoBarrier(), automated.autoBarrier)
        val strict = question.copy(isSkippable = false)
        spec.assertIgnored(ask(item = strict), QuestionnaireIntent.Public.Answer(question.id, Answer.Skipped))
    }

    @Test
    fun `default provenance is manual even with generation batch snapshot and opaque native context`() {
        val asked = ask()
        val context = object : QuestionnaireAnswerContext { }
        val result = assertNotNull(
            resolveAfterManualAdmission(
                asked,
                QuestionnaireIntent.Public.Answer(
                    question.id,
                    Answer.Confirmed(true),
                    context,
                    asked.generations.getValue(question.id),
                    asked.generations,
                ),
            ),
        )
        assertTrue(assertIs<QuestionnaireState.Asking>(result.to).autoBarrier.isBlocked(question.source))
        assertEquals(context, assertIs<QuestionnaireOutput.Answered>(result.outputs.single()).context)
    }

    @Test
    fun `explicit native auto requires captured generation and never opens a blocked source`() {
        val asked = ask()
        val automatic = auto(asked)
        spec.assertIgnored(asked, automatic.copy(expectedGeneration = null))
        spec.assertIgnored(asked, automatic.copy(expectedGeneration = "wrong"))
        spec.assertIgnored(QuestionnaireState.Asking(listOf(question)), automatic)
        val automated = transition(asked, automatic)
        assertFalse(automated.autoBarrier.isBlocked(question.source))
        val manual = transition(asked, automatic.copy(origin = QuestionnaireAnswerOrigin.Manual))
        val replacement = ask(manual)
        spec.assertIgnored(replacement, auto(replacement))
        val stillManual = transition(
            replacement,
            QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(false)),
        )
        assertTrue(stillManual.autoBarrier.isBlocked(question.source))
    }

    @Test
    fun `withdraw replacement restore seal and retry never clear a manual source barrier`() {
        val asked = ask()
        val manual = transition(asked, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
        val generation = manual.generations.getValue(question.id)
        val preparing = transition(manual, QuestionnaireIntent.Public.BeginDelivery(question.id, generation))
        val accept = QuestionnaireIntent.Public.AcceptDelivery(question.id, generation)
        val accepted = transition(preparing, accept)
        val retry = transition(
            accepted,
            QuestionnaireIntent.Public.FailDelivery(question.id, generation, accept.receiptToken),
        )
        val done = transition(accepted, QuestionnaireIntent.Public.CompleteDelivery(question.id, accept.receiptToken))
        val withdrawn = transition(manual, QuestionnaireIntent.Public.Withdraw(question.id))
        val sealed = transition(withdrawn, QuestionnaireIntent.Public.FinishInitialRestore)
        for (state in listOf(preparing, accepted, retry, done, withdrawn, sealed, ask(manual))) {
            assertEquals(manual.autoBarrier, state.autoBarrier)
            val nextBatch = question.copy(id = QuestionnaireId("next-batch"))
            val next = ask(state, nextBatch)
            spec.assertIgnored(next, auto(next, nextBatch))
        }
    }

    @Test
    fun `other sources remain independent and restored batches of blocked source stay blocked`() {
        val manual = transition(ask(), QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
        val other = question.copy(id = QuestionnaireId("independent"), source = "other-source")
        val another = ask(manual, other)
        val automated = transition(another, auto(another, other))
        assertFalse(automated.autoBarrier.isBlocked(other.source))
        assertTrue(automated.autoBarrier.isBlocked(question.source))
        val restored = question.copy(id = QuestionnaireId("restored"))
        val restoring = transition(automated, QuestionnaireIntent.Public.Restore(restored))
        spec.assertIgnored(restoring, auto(restoring, restored))
    }

    @Test
    fun `source limit activates sticky global block without evicting safety and manual answers still work`() {
        var state: QuestionnaireState.Asking = QuestionnaireState.Asking(emptyList())
        repeat(QuestionnaireAutoBarrier.LIMIT) { index ->
            val item = question.copy(id = QuestionnaireId("q-$index"), source = "source-$index")
            state = ask(state, item)
            state = transition(state, QuestionnaireIntent.Public.Answer(item.id, Answer.Confirmed(true)))
            state = transition(state, QuestionnaireIntent.Public.Withdraw(item.id))
        }
        assertEquals(QuestionnaireAutoBarrier.LIMIT, state.autoBarrier.sources.size)
        assertFalse(state.autoBarrier.isGloballyBlocked)
        val newSource = ask(state)
        assertNotNull(spec.resolve(newSource, auto(newSource)))
        val saturated = transition(newSource, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
        assertTrue(saturated.autoBarrier.isGloballyBlocked)
        assertTrue(saturated.autoBarrier.sources.isEmpty())
        val empty = transition(saturated, QuestionnaireIntent.Public.Withdraw(question.id))
        val sealed = transition(empty, QuestionnaireIntent.Public.FinishInitialRestore)
        for (source in listOf("source-0", "never-seen", question.source)) {
            val item = question.copy(source = source)
            val asked = ask(sealed, item)
            spec.assertIgnored(asked, auto(asked, item))
            val manual = transition(asked, QuestionnaireIntent.Public.Answer(item.id, Answer.Confirmed(false)))
            assertEquals(saturated.autoBarrier, manual.autoBarrier)
        }
    }

    @Test
    fun `repeated manual source at capacity does not trigger global block`() {
        val sources = (0 until QuestionnaireAutoBarrier.LIMIT).map { "s-$it" }.toSet()
        val state = QuestionnaireState.Asking(emptyList(), autoBarrier = QuestionnaireAutoBarrier(sources))
        val item = question.copy(source = sources.first())
        val asked = ask(state, item)
        val answered = transition(asked, QuestionnaireIntent.Public.Answer(item.id, Answer.Confirmed(false)))
        assertEquals(state.autoBarrier, answered.autoBarrier)
        assertFalse(answered.autoBarrier.isGloballyBlocked)
    }

    @Test
    fun `oversized source fails closed while maximum sized source fits`() {
        val fits = question.copy(source = "s".repeat(QuestionnaireAutoBarrier.MAX_SOURCE_LENGTH))
        val fitsAnswer = transition(
            ask(item = fits),
            QuestionnaireIntent.Public.Answer(fits.id, Answer.Confirmed(true)),
        )
        assertFalse(fitsAnswer.autoBarrier.isGloballyBlocked)
        assertTrue(fitsAnswer.autoBarrier.isBlocked(fits.source))
        val oversized = fits.copy(source = fits.source + "s")
        val answered = transition(
            ask(item = oversized),
            QuestionnaireIntent.Public.Answer(oversized.id, Answer.Confirmed(true)),
        )
        assertTrue(answered.autoBarrier.isGloballyBlocked)
        assertTrue(answered.autoBarrier.sources.isEmpty())
    }

    @Test
    fun `process crash requires durable lock from host and dropped outputs do not unlock live machine`() {
        val asked = ask()
        val committed = assertNotNull(
            spec.resolve(asked, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true))),
        )
        val replacement = ask(committed.to)
        spec.assertIgnored(replacement, auto(replacement))
        assertNull(spec.persistence)
        // New machine has no process-lifetime state. Host must consult its durable context lock before Auto.
        val fresh = ask(spec.initial)
        assertNotNull(spec.resolve(fresh, auto(fresh)))
    }

    private fun ask(
        state: QuestionnaireState = QuestionnaireState.Idle,
        item: Questionnaire = question,
    ): QuestionnaireState.Asking = transition(state, QuestionnaireIntent.Public.Ask(item))

    private fun auto(
        state: QuestionnaireState.Asking,
        item: Questionnaire = question,
    ): QuestionnaireIntent.Public.Answer = QuestionnaireIntent.Public.Answer(
        item.id,
        Answer.Confirmed(true),
        expectedGeneration = state.generations.getValue(item.id),
        origin = QuestionnaireAnswerOrigin.Auto,
    )

    private fun transition(state: QuestionnaireState, intent: QuestionnaireIntent): QuestionnaireState.Asking =
        assertIs<QuestionnaireState.Asking>(assertNotNull(resolveAfterManualAdmission(state, intent)).to)
}
