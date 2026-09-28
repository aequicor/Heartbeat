package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.toQuestionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioQuestionBridgeTest {
    private val permission = StudioPermission(
        "s1",
        "r1",
        "Run tests?",
        listOf(StudioPermissionOption("allow", "Allow"), StudioPermissionOption("deny", "Deny")),
    )
    private val question = permission.toQuestionnaire()

    @Test
    fun `live permission is answered natively and withdrawn once the engine resolves it`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        runCurrent()
        assertEquals(listOf<QuestionnaireIntent>(QuestionnaireIntent.Public.Ask(question)), fixture.queue.sent)

        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(true)))
        runCurrent()
        assertEquals(listOf("s1/r1/allow"), fixture.runtime.responses)

        fixture.runtime.state.value = StudioRuntimeState()
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Withdraw(question.id), fixture.queue.sent.last())
    }

    @Test
    fun `restored questions stay open without a native request and are answered by a follow-up message`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState()
        runCurrent()
        assertTrue(fixture.queue.sent.isEmpty())

        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(false)))
        runCurrent()
        assertTrue(fixture.runtime.responses.isEmpty())
        assertEquals(listOf("s1: Run tests?\nDeny"), fixture.runtime.prompts)
        assertEquals(
            listOf<QuestionnaireIntent>(QuestionnaireIntent.Public.Withdraw(question.id)),
            fixture.queue.sent,
        )
    }

    @Test
    fun `a native request lost while answering falls back to a follow-up message`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        fixture.runtime.isRequestGone = true
        runCurrent()
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(true)))
        runCurrent()
        assertEquals(listOf("s1: Run tests?\nAllow"), fixture.runtime.prompts)
    }

    private class Fixture(scope: TestScope) {
        val runtime = FakeRuntime()
        val queue = FakeQueue()
        val bridge = StudioQuestionBridge(
            runtime,
            queue.registry,
            EnabledToggles,
            TestScopeHandle(scope.backgroundScope),
        )

        init {
            bridge.start()
        }
    }
}

private object EnabledToggles : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // Every toggle read by the bridge is a flag.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(true as T)

    @Suppress("UNCHECKED_CAST") // Every toggle read by the bridge is a flag.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = true as T
}

private class FakeRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())
    val responses = mutableListOf<String>()
    val prompts = mutableListOf<String>()
    var isRequestGone = false

    override suspend fun defaults(): RunSettings = DefaultRunSettings

    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome {
        prompts += "$sessionId: $prompt"
        return RunOutcome.Completed
    }

    override suspend fun cancel(sessionId: String) = Unit

    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) {
        check(!isRequestGone) { "the request is no longer pending" }
        responses += "$sessionId/$requestId/$optionId"
    }
}

private class FakeQueue : MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput> {
    override val name = "questionnaire"
    override val state = MutableStateFlow<QuestionnaireState>(QuestionnaireState.Idle)
    override val outputs = MutableSharedFlow<QuestionnaireOutput>()
    val sent = mutableListOf<QuestionnaireIntent>()

    override suspend fun send(intent: QuestionnaireIntent.Public): SendResult {
        sent += intent
        return SendResult.Accepted
    }

    val registry = object : MachineRegistry {
        @Suppress("UNCHECKED_CAST") // The test registry serves only the questionnaire key.
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O> = this@FakeQueue as MachineRef<S, P, O>

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult = find(key).send(intent)
    }
}

private class TestScopeHandle(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test/profile"
    override val isClosed = false
    override val savedState = object : ScopeSavedState {
        override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
        override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
        override fun unregister(key: String) = Unit
        override fun snapshot() = SavedBundle(emptyMap())
    }

    override fun onClose(action: () -> Unit) = DisposableHandle { }
}
