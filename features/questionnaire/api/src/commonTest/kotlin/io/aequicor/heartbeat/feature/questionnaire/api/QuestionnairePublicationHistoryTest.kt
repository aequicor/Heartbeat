package io.aequicor.heartbeat.feature.questionnaire.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuestionnairePublicationHistoryTest {
    private val spec = QuestionnaireMachineSpec
    private val question = Questionnaire(QuestionnaireId("q"), "s", "Continue?", Question.Confirm("Yes", "No"))

    @Test
    fun `old G1 cannot restore after G2 was asked and withdrawn even with no pending questions`() {
        val original = ask(QuestionnaireState.Idle)
        val oldGeneration = original.generations.getValue(question.id)
        val other = question.copy(id = QuestionnaireId("other"))
        val withOther = transition(original, QuestionnaireIntent.Public.Ask(other))
        for (state in listOf(original, withOther)) {
            val replacement = ask(state)
            val generation = replacement.generations.getValue(question.id)
            val withdrawn = transition(replacement, QuestionnaireIntent.Public.Withdraw(question.id))
            assertEquals(generation, withdrawn.publications.latest[question.id])
            spec.assertIgnored(withdrawn, QuestionnaireIntent.Public.Ask(question, oldGeneration, true))
            spec.assertIgnored(withdrawn, QuestionnaireIntent.Public.Restore(question))
            spec.assertIgnored(withdrawn, QuestionnaireIntent.Public.BeginDelivery(question.id, oldGeneration))
            spec.assertIgnored(withdrawn, QuestionnaireIntent.Public.Withdraw(question.id, oldGeneration))
        }
    }

    @Test
    fun `conditional absent ask requires positive retained identity rather than mere absence`() {
        val original = ask(QuestionnaireState.Idle)
        val generation = original.generations.getValue(question.id)
        val reopen = QuestionnaireIntent.Public.Ask(question, generation, true)
        val withdrawn = transition(original, QuestionnaireIntent.Public.Withdraw(question.id))
        assertEquals(reopen.generation, transition(withdrawn, reopen).generations[question.id])
        spec.assertIgnored(withdrawn, reopen.copy(isAbsentAllowed = false))
        spec.assertIgnored(QuestionnaireState.Idle, reopen)
        spec.assertIgnored(QuestionnaireState.Asking(emptyList()), reopen)
    }

    @Test
    fun `restoration checks a touch before first publication including pristine idle withdrawal`() {
        val restore = QuestionnaireIntent.Public.Restore(question)
        val touched = transition(QuestionnaireState.Idle, QuestionnaireIntent.Public.Withdraw(question.id))
        assertTrue(touched.pending.isEmpty())
        assertTrue(question.id in touched.publications.latest)
        spec.assertIgnored(touched, restore)
        val other = question.copy(id = QuestionnaireId("other"))
        val restoredOther = transition(touched, QuestionnaireIntent.Public.Restore(other))
        assertEquals(listOf(other), restoredOther.pending)
        spec.assertIgnored(restoredOther, restore)
    }

    @Test
    fun `journal cannot replace pending submitting or preparing generations`() {
        val asked = ask(QuestionnaireState.Idle)
        val answered = transition(asked, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
        val preparing = transition(
            answered,
            QuestionnaireIntent.Public.BeginDelivery(question.id, asked.generations.getValue(question.id)),
        )
        for (state in listOf(asked, answered, preparing, QuestionnaireState.Asking(listOf(question)))) {
            spec.assertIgnored(state, QuestionnaireIntent.Public.Restore(question.copy(title = "Stale journal")))
        }
    }

    @Test
    fun `restore wins before an explicit ask and the later ask still replaces it`() {
        val restore = QuestionnaireIntent.Public.Restore(question)
        val restored = transition(QuestionnaireState.Idle, restore)
        assertEquals(restore.generation, restored.generations[question.id])
        spec.assertIgnored(restored, restore)
        val explicit = question.copy(title = "New content")
        val replacement = transition(restored, QuestionnaireIntent.Public.Ask(explicit))
        assertEquals(listOf(explicit), replacement.pending)
        assertNotEquals(restore.generation, replacement.generations[question.id])
        spec.assertIgnored(replacement, restore)
    }

    @Test
    fun `restoration and publication history are isolated between machines`() {
        val intent = QuestionnaireIntent.Public.Restore(question)
        val first = transition(QuestionnaireState.Idle, intent)
        val withdrawn = transition(first, QuestionnaireIntent.Public.Withdraw(question.id))
        spec.assertIgnored(withdrawn, intent)
        val second = transition(QuestionnaireState.Idle, intent.copy())
        assertEquals(listOf(question), second.pending)
        assertNotEquals(first.generations, second.generations)
    }

    @Test
    fun `bounded history cannot forget a newer publication and authorize an older absent one`() {
        val original = ask(QuestionnaireState.Idle)
        val generation = original.generations.getValue(question.id)
        val full = QuestionnairePublicationHistory(
            latest = mapOf(question.id to generation) + (1 until QuestionnairePublicationHistory.LIMIT).associate {
                QuestionnaireId("old-$it") to "generation-$it"
            },
        )
        val idle = QuestionnaireState.Asking(emptyList(), publications = full)
        val overflow = transition(idle, QuestionnaireIntent.Public.Withdraw(QuestionnaireId("new")))
        assertEquals(QuestionnairePublicationHistory.LIMIT, overflow.publications.latest.size)
        assertEquals(QuestionnairePublicationHistory.LIMIT + 1, overflow.publications.touched.size)
        assertFalse(question.id in overflow.publications.latest)
        spec.assertIgnored(overflow, QuestionnaireIntent.Public.Ask(question, generation, true))
        spec.assertIgnored(overflow, QuestionnaireIntent.Public.Restore(question))
        val untouched = question.copy(id = QuestionnaireId("untouched"))
        assertEquals(listOf(untouched), transition(overflow, QuestionnaireIntent.Public.Restore(untouched)).pending)
        // Explicit sources and manual UI keep working after saturation.
        val fresh = ask(overflow)
        val answered = transition(fresh, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
        val preparing = transition(
            answered,
            QuestionnaireIntent.Public.BeginDelivery(question.id, fresh.generations.getValue(question.id)),
        )
        val removed = transition(preparing, QuestionnaireIntent.Public.Withdraw(question.id))
        assertEquals(QuestionnairePublicationHistory.LIMIT, removed.publications.latest.size)
        assertEquals(overflow.publications.touched, removed.publications.touched)
        val reopen = QuestionnaireIntent.Public.Ask(question, fresh.generations.getValue(question.id), true)
        assertEquals(reopen.generation, transition(removed, reopen).generations[question.id])
        spec.assertIgnored(removed, QuestionnaireIntent.Public.Ask(question, generation, true))
    }

    @Test
    fun `pending generations remain valid when historical records are evicted`() {
        val asked = ask(QuestionnaireState.Idle)
        val generation = asked.generations.getValue(question.id)
        val withoutRecord = asked.copy(publications = QuestionnairePublicationHistory(touched = setOf(question.id)))
        val answered = transition(withoutRecord, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
        val preparing = transition(answered, QuestionnaireIntent.Public.BeginDelivery(question.id, generation))
        val idle = transition(preparing, QuestionnaireIntent.Public.AcceptDelivery(question.id, generation))
        assertEquals(generation, idle.deliveryReceipts.getValue(question.id).generation)
        assertEquals(null, idle.publications.latest[question.id])
        assertEquals(setOf(question.id), idle.publications.touched)
    }

    @Test
    fun `independent resolutions from the same snapshot cannot share restore exclusions`() {
        val full = QuestionnairePublicationHistory(
            latest = (0 until QuestionnairePublicationHistory.LIMIT).associate {
                QuestionnaireId("old-$it") to "generation-$it"
            },
        )
        val base = QuestionnaireState.Asking(emptyList(), publications = full)
        val restore = QuestionnaireIntent.Public.Restore(question)
        // Concurrent resolvers can evaluate the same immutable snapshot in either order.
        val restored = transition(base, restore)
        val withdrawn = transition(base, QuestionnaireIntent.Public.Withdraw(question.id))
        assertEquals(restored, transition(base, restore))
        assertEquals(QuestionnairePublicationHistory.LIMIT, base.publications.touched.size)
        assertFalse(question.id in base.publications.touched)
        spec.assertIgnored(withdrawn, restore)
        spec.assertIgnored(restored, restore)
        val other = question.copy(id = QuestionnaireId("other"))
        val next = transition(withdrawn, QuestionnaireIntent.Public.Restore(other))
        assertEquals(QuestionnairePublicationHistory.LIMIT + 2, next.publications.touched.size)
        assertEquals(QuestionnairePublicationHistory.LIMIT, next.publications.latest.size)
        assertFalse(other.id in withdrawn.publications.touched)
        assertEquals(next, Json.decodeFromString<QuestionnaireState>(Json.encodeToString<QuestionnaireState>(next)))
    }

    @Test
    fun `new metadata decodes with defaults and round trips even in empty queue`() {
        val legacy = Json.decodeFromString<QuestionnaireState.Asking>("""{"pending":[]}""")
        assertEquals(QuestionnaireState.Asking(emptyList()), legacy)
        val touched = transition(QuestionnaireState.Idle, QuestionnaireIntent.Public.Withdraw(question.id))
        val decoded = Json.decodeFromString<QuestionnaireState>(Json.encodeToString<QuestionnaireState>(touched))
        assertEquals(touched, decoded)
        spec.assertIgnored(decoded, QuestionnaireIntent.Public.Restore(question))
    }

    @Test
    fun `restore nonce is allocated before pure transition evaluation`() {
        val restore = QuestionnaireIntent.Public.Restore(question)
        assertEquals(transition(QuestionnaireState.Idle, restore), transition(QuestionnaireState.Idle, restore))
        assertNotEquals(
            transition(QuestionnaireState.Idle, restore),
            transition(QuestionnaireState.Idle, restore.copy()),
        )
    }

    @Test
    fun `finish initial restore seals idle and populated queues without changing publications`() {
        val asked = ask(QuestionnaireState.Idle)
        val answered = transition(asked, QuestionnaireIntent.Public.Answer(question.id, Answer.Confirmed(true)))
        val preparing = transition(
            answered,
            QuestionnaireIntent.Public.BeginDelivery(question.id, asked.generations.getValue(question.id)),
        )
        for (before in listOf(QuestionnaireState.Idle, asked, answered, preparing)) {
            val sealed = transition(before, QuestionnaireIntent.Public.FinishInitialRestore)
            val expected = before as? QuestionnaireState.Asking ?: QuestionnaireState.Asking(emptyList())
            assertEquals(
                expected.copy(
                    publications = expected.publications.copy(touched = emptySet(), isInitialRestoreFinished = true),
                ),
                sealed,
            )
            spec.assertIgnored(sealed, QuestionnaireIntent.Public.FinishInitialRestore)
            val untouched = question.copy(id = QuestionnaireId("untouched"))
            spec.assertIgnored(sealed, QuestionnaireIntent.Public.Restore(untouched))
            val decoded = Json.decodeFromString<QuestionnaireState>(Json.encodeToString<QuestionnaireState>(sealed))
            assertEquals(sealed, decoded)
            spec.assertIgnored(decoded, QuestionnaireIntent.Public.Restore(question))
        }
    }

    @Test
    fun `live id churn never grows exact restore exclusions before seal`() {
        var state: QuestionnaireState = QuestionnaireState.Idle
        repeat(QuestionnairePublicationHistory.LIMIT + 2) {
            val live = question.copy(id = QuestionnaireId("${LIVE_QUESTION_ID_PREFIX}$it"))
            spec.assertIgnored(state, QuestionnaireIntent.Public.Restore(live))
            state = transition(state, QuestionnaireIntent.Public.Ask(live))
            state = transition(state, QuestionnaireIntent.Public.Withdraw(live.id))
        }
        val current = assertIs<QuestionnaireState.Asking>(state)
        assertTrue(current.publications.touched.isEmpty())
        assertEquals(QuestionnairePublicationHistory.LIMIT, current.publications.latest.size)
        assertFalse(current.publications.isInitialRestoreFinished)
        assertEquals(listOf(question), transition(current, QuestionnaireIntent.Public.Restore(question)).pending)
    }

    @Test
    fun `sealed history remains bounded through churn and preserves current and recent generations`() {
        val asked = ask(QuestionnaireState.Idle)
        val activeGeneration = asked.generations.getValue(question.id)
        var state = transition(asked, QuestionnaireIntent.Public.FinishInitialRestore)
        repeat(QuestionnairePublicationHistory.LIMIT * 2) {
            val other = question.copy(id = QuestionnaireId("churn-$it"))
            state = transition(state, QuestionnaireIntent.Public.Ask(other))
            state = transition(state, QuestionnaireIntent.Public.Withdraw(other.id))
        }
        assertTrue(state.publications.touched.isEmpty())
        assertEquals(QuestionnairePublicationHistory.LIMIT, state.publications.latest.size)
        assertEquals(mapOf(question.id to activeGeneration), state.generations)
        assertFalse(question.id in state.publications.latest)
        val lastId = QuestionnaireId("churn-${QuestionnairePublicationHistory.LIMIT * 2 - 1}")
        val recent = question.copy(id = lastId)
        val reopen = QuestionnaireIntent.Public.Ask(recent, state.publications.latest.getValue(lastId), true)
        assertEquals(reopen.generation, transition(state, reopen).generations[lastId])
        val retired = transition(state, QuestionnaireIntent.Public.Withdraw(question.id, activeGeneration))
        assertEquals(activeGeneration, retired.publications.latest[question.id])
        spec.assertIgnored(retired, QuestionnaireIntent.Public.Restore(question))
        assertTrue(retired.publications.touched.isEmpty())
    }

    private fun ask(state: QuestionnaireState): QuestionnaireState.Asking =
        transition(state, QuestionnaireIntent.Public.Ask(question))

    private fun transition(state: QuestionnaireState, intent: QuestionnaireIntent): QuestionnaireState.Asking =
        assertIs<QuestionnaireState.Asking>(assertNotNull(spec.resolve(state, intent)).to)
}
