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

class QuestionnaireJournalTest {
    private val saved = Questionnaire(QuestionnaireId("s1/r1"), "s1", "Go?", Question.Confirm("Yes", "No"))

    @Test
    fun `saved questions are asked again and every queue change is saved`() = runTest {
        val storage = MemoryStorage(listOf(saved))
        val machine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertEquals(listOf<QuestionnaireIntent>(QuestionnaireIntent.Public.Ask(saved)), machine.sent)

        machine.state.value = QuestionnaireState.Asking(listOf(saved))
        runCurrent()
        assertEquals(listOf(saved), storage.pending)
        machine.state.value = QuestionnaireState.Idle
        runCurrent()
        assertEquals(emptyList(), storage.pending)
    }

    private class MemoryStorage(var pending: List<Questionnaire>) : QuestionnaireStorage {
        override suspend fun load() = pending

        override suspend fun save(pending: List<Questionnaire>) {
            this.pending = pending
        }
    }

    private class RecordingMachine : Machine<QuestionnaireState, QuestionnaireIntent, QuestionnaireOutput> {
        override val name = "questionnaire"
        override val state = MutableStateFlow<QuestionnaireState>(QuestionnaireState.Idle)
        override val outputs = MutableSharedFlow<QuestionnaireOutput>()
        val sent = mutableListOf<QuestionnaireIntent>()

        override suspend fun send(intent: QuestionnaireIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
}
