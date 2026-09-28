package io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Choice
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRoute
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private typealias ScreenProvider =
    Provider<QuestionnaireScreenState, QuestionnaireScreenIntent, QuestionnaireScreenAction>

class QuestionnaireModelTest {
    private val multi = Questionnaire(
        QuestionnaireId("m"),
        "s1",
        "Pick",
        Question.MultiChoice(listOf(Choice("a", "A"), Choice("b", "B"), Choice("c", "C")), min = 1, max = 2),
    )
    private val text = Questionnaire(QuestionnaireId("t"), "s1", "Name", Question.FreeText())
    private val foreign = Questionnaire(QuestionnaireId("f"), "s2", "Other", Question.Confirm("y", "n"))

    @Test
    fun `only questions of the shown source are reflected with their submitting state`() = runTest {
        val fixture = Fixture(this, QuestionnaireState.Asking(listOf(multi, foreign, text), setOf(text.id)))
        val screen = fixture.subscribe()
        val questions = screen.states.value.questions
        assertEquals(listOf("m", "t"), questions.map { it.id })
        assertTrue(questions.last().isSubmitting)
        fixture.machine.state.value = QuestionnaireState.Idle
        runCurrent()
        assertTrue(screen.states.value.questions.isEmpty())
    }

    @Test
    fun `multi choice draft respects the maximum and submits the selection in choice order`() = runTest {
        val fixture = Fixture(this, QuestionnaireState.Asking(listOf(multi)))
        val screen = fixture.subscribe()
        listOf("c", "a", "b").forEach { screen.intent(QuestionnaireScreenIntent.ToggleChoice("m", it)) }
        runCurrent()
        val question = screen.states.value.questions.single()
        assertEquals(setOf("a", "c"), question.selected.toSet())
        assertTrue(question.isAnswerReady)
        screen.intent(QuestionnaireScreenIntent.Submit("m"))
        runCurrent()
        assertEquals(
            listOf<QuestionnaireIntent>(QuestionnaireIntent.Public.Answer(multi.id, Answer.Selected(listOf("a", "c")))),
            fixture.machine.sent,
        )
    }

    @Test
    fun `drafts survive queue updates and blank text cannot be submitted`() = runTest {
        val fixture = Fixture(this, QuestionnaireState.Asking(listOf(text)))
        val screen = fixture.subscribe()
        screen.intent(QuestionnaireScreenIntent.Submit("t"))
        screen.intent(QuestionnaireScreenIntent.TextChanged("t", "Heartbeat"))
        runCurrent()
        assertTrue(fixture.machine.sent.isEmpty())
        fixture.machine.state.value = QuestionnaireState.Asking(listOf(text, multi))
        runCurrent()
        assertEquals("Heartbeat", screen.states.value.questions.first().text)
        screen.intent(QuestionnaireScreenIntent.Skip("t"))
        screen.intent(QuestionnaireScreenIntent.Confirm("f", false))
        runCurrent()
        assertEquals(
            listOf<QuestionnaireIntent>(
                QuestionnaireIntent.Public.Answer(text.id, Answer.Skipped),
                QuestionnaireIntent.Public.Answer(foreign.id, Answer.Confirmed(false)),
            ),
            fixture.machine.sent,
        )
        assertFalse(screen.states.value.questions.first().isSubmitting)
    }

    private class Fixture(private val scope: TestScope, initial: QuestionnaireState) {
        val machine = FakeMachine(initial)
        val model = QuestionnaireModel(
            machine = machine,
            route = QuestionnaireRoute("s1"),
            scope = TestScopeHandle(scope.backgroundScope),
            factory = HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler))),
        )

        suspend fun subscribe(): ScreenProvider {
            val provider = CompletableDeferred<ScreenProvider>()
            scope.backgroundScope.launch {
                model.store.collect {
                    provider.complete(this)
                    awaitCancellation()
                }
            }
            scope.runCurrent()
            return provider.await()
        }
    }
}

private class FakeMachine(initial: QuestionnaireState) :
    Machine<QuestionnaireState, QuestionnaireIntent, QuestionnaireOutput> {
    override val name = "questionnaire"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<QuestionnaireOutput>()
    val sent = mutableListOf<QuestionnaireIntent>()

    override suspend fun send(intent: QuestionnaireIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}

private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}

private class TestScopeHandle(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test/questionnaire"
    override val isClosed = false
    override val savedState = object : ScopeSavedState {
        override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
        override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
        override fun unregister(key: String) = Unit
        override fun snapshot() = SavedBundle(emptyMap())
    }

    override fun onClose(action: () -> Unit) = DisposableHandle { }
}
