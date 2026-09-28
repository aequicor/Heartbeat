package io.aequicor.heartbeat.feature.questionnaire.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestionnaireJournalTest {
    private val saved = Questionnaire(QuestionnaireId("s1/r1"), "s1", "Go?", Question.Confirm("Yes", "No"))

    @Test
    fun `saved questions are asked again and every queue change is saved`() = runTest {
        val storage = MemoryStorage(listOf(saved))
        val machine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertEquals(listOf<QuestionnaireIntent>(QuestionnaireIntent.Public.Ask(saved)), machine.sent)
        // The restored queue equals the saved one: nothing is written.
        assertEquals(0, storage.saves)

        machine.state.value = QuestionnaireState.Idle
        runCurrent()
        assertEquals(emptyList(), storage.pending)
    }

    @Test
    fun `a rejected saved question never stops the journal`() = runTest {
        val storage = MemoryStorage(listOf(saved))
        val machine = RecordingMachine().apply { result = SendResult.Ignored }
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertEquals(emptyList(), storage.pending)

        val next = saved.copy(id = QuestionnaireId("s1/r2"))
        machine.state.value = QuestionnaireState.Asking(listOf(next))
        runCurrent()
        assertEquals(listOf(next), storage.pending)
    }

    @Test
    fun `answers in flight are not saved`() = runTest {
        val storage = MemoryStorage(emptyList())
        val machine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        val other = saved.copy(id = QuestionnaireId("s1/r2"))
        machine.state.value = QuestionnaireState.Asking(listOf(saved, other), submitting = setOf(saved.id))
        runCurrent()
        assertEquals(listOf(other), storage.pending)
    }

    @Test
    fun `storage failures are logged and the journal keeps saving`() = runTest {
        val storage = MemoryStorage(emptyList()).apply { failures = 2 }
        val machine = RecordingMachine()
        val journal = backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()

        machine.state.value = QuestionnaireState.Asking(listOf(saved))
        runCurrent()
        machine.state.value = QuestionnaireState.Idle
        runCurrent()
        machine.state.value = QuestionnaireState.Asking(listOf(saved))
        runCurrent()
        assertTrue(journal.isActive)
        assertEquals(listOf(saved), storage.pending)
    }

    private class MemoryStorage(var pending: List<Questionnaire>) : QuestionnaireStorage {
        var failures = 0
        var saves = 0

        override suspend fun load(): List<Questionnaire> {
            if (failures > 0) {
                failures--
                error("load failed")
            }
            return pending
        }

        override suspend fun save(pending: List<Questionnaire>) {
            if (failures > 0) {
                failures--
                error("save failed")
            }
            saves++
            this.pending = pending
        }
    }

    private class RecordingMachine : Machine<QuestionnaireState, QuestionnaireIntent, QuestionnaireOutput> {
        override val name = "questionnaire"
        override val state = MutableStateFlow<QuestionnaireState>(QuestionnaireState.Idle)
        override val outputs = MutableSharedFlow<QuestionnaireOutput>()
        val sent = mutableListOf<QuestionnaireIntent>()
        var result: SendResult = SendResult.Accepted

        /** Applies accepted asks before returning, like the real machine. */
        override suspend fun send(intent: QuestionnaireIntent): SendResult {
            sent += intent
            if (result == SendResult.Accepted && intent is QuestionnaireIntent.Public.Ask) {
                val pending = (state.value as? QuestionnaireState.Asking)?.pending.orEmpty()
                state.value = QuestionnaireState.Asking(pending + intent.questionnaire)
            }
            return result
        }
    }
}
