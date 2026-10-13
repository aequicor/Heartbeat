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
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireAnswerContext
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRoute
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenAction
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private typealias ScreenProvider =
    Provider<QuestionnaireScreenState, QuestionnaireModelInput, QuestionnaireScreenAction>

private fun ScreenProvider.send(intent: QuestionnaireScreenIntent) = intent(QuestionnaireModelInput(intent))

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
    fun `remote answer dispatch carries native context outside the serialized intent`() = runTest {
        val fixture = Fixture(this, QuestionnaireState.Asking(listOf(text)))
        fixture.subscribe()
        val context = object : QuestionnaireAnswerContext { }
        fixture.model.dispatch(QuestionnaireScreenIntent.TextChanged("t", "Ready"))
        fixture.model.dispatch(QuestionnaireScreenIntent.Submit("t"), context)
        assertEquals(
            listOf<QuestionnaireIntent>(QuestionnaireIntent.Public.Answer(text.id, Answer.Text("Ready"), context)),
            fixture.machine.sent,
        )
    }

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
        listOf("c", "a", "b").forEach { screen.send(QuestionnaireScreenIntent.ToggleChoice("m", it)) }
        runCurrent()
        val question = screen.states.value.questions.single()
        assertEquals(setOf("a", "c"), question.selected.toSet())
        assertTrue(question.isAnswerReady)
        screen.send(QuestionnaireScreenIntent.Submit("m"))
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
        screen.send(QuestionnaireScreenIntent.Submit("t"))
        screen.send(QuestionnaireScreenIntent.TextChanged("t", "Heartbeat"))
        runCurrent()
        assertTrue(fixture.machine.sent.isEmpty())
        fixture.machine.state.value = QuestionnaireState.Asking(listOf(text, multi))
        runCurrent()
        assertEquals("Heartbeat", screen.states.value.questions.first().text)
        screen.send(QuestionnaireScreenIntent.Skip("t"))
        screen.send(QuestionnaireScreenIntent.Confirm("f", false))
        runCurrent()
        assertEquals(
            listOf<QuestionnaireIntent>(
                QuestionnaireIntent.Public.Answer(text.id, Answer.Skipped),
            ),
            fixture.machine.sent,
        )
        assertFalse(screen.states.value.questions.first().isSubmitting)
    }

    @Test
    fun `remote dispatch acknowledges a committed draft and waits for the machine on submit`() = runTest {
        val fixture = Fixture(this, QuestionnaireState.Asking(listOf(text)))
        fixture.subscribe()
        val draft = fixture.model.dispatch(QuestionnaireScreenIntent.TextChanged("t", "Ready"))
        assertEquals("Ready", draft.questions.single().text)
        assertTrue(draft.revision > 0)
        fixture.machine.gate = CompletableDeferred()
        val submitted = async { fixture.model.dispatch(QuestionnaireScreenIntent.Submit("t")) }
        runCurrent()
        assertFalse(submitted.isCompleted)
        fixture.machine.gate?.complete(Unit)
        assertEquals("Ready", submitted.await().questions.single().text)
        assertEquals(Answer.Text("Ready"), (fixture.machine.sent.single() as QuestionnaireIntent.Public.Answer).answer)
    }

    @Test
    fun `foreign confirmation and skip are never sent and closing the screen cancels dispatch`() = runTest {
        val fixture = Fixture(this, QuestionnaireState.Asking(listOf(text, foreign)))
        fixture.subscribe()
        fixture.model.dispatch(QuestionnaireScreenIntent.Confirm("f", true))
        fixture.model.dispatch(QuestionnaireScreenIntent.Skip("f"))
        assertTrue(fixture.machine.sent.isEmpty())
        fixture.machine.gate = CompletableDeferred()
        val pending = async { fixture.model.dispatch(QuestionnaireScreenIntent.Skip("t")) }
        runCurrent()
        assertFalse(pending.isCompleted)
        fixture.owner.cancel()
        assertFailsWith<CancellationException> { pending.await() }
    }

    private class Fixture(private val scope: TestScope, initial: QuestionnaireState) {
        val owner = CoroutineScope(
            scope.backgroundScope.coroutineContext + SupervisorJob(scope.backgroundScope.coroutineContext.job),
        )
        val machine = FakeMachine(initial)
        val model = QuestionnaireModel(
            machine = machine,
            route = QuestionnaireRoute("s1"),
            scope = TestScopeHandle(owner),
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
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun send(intent: QuestionnaireIntent): SendResult {
        gate?.await()
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
