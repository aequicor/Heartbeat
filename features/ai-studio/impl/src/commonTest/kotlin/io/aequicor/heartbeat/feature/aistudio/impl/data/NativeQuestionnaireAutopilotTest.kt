package io.aequicor.heartbeat.feature.aistudio.impl.data

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
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionInput
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.toQuestionnaire
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineSpec
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeQuestionnaireAutopilotTest {
    @Test
    fun `every native input stays manual without model dependencies or automatic delivery`() = runTest {
        val f = NativeFixture(this)
        for ((input, _) in nativeInputs) {
            val permission = nativePermission.copy(input = input)
            f.show(permission)
            runCurrent()
            assertEquals(listOf(assertNotNull(permission.toQuestionnaire())), f.queue.pending)
            assertTrue(f.queue.answers.isEmpty())
            assertTrue(f.runtime.responses.isEmpty())
            assertEquals(0, f.runtime.modelCalls)
            assertEquals(0, f.modelSettingsRequests)
        }
        f.show(nativePermission.copy(input = null))
        runCurrent()
        assertEquals(1, f.queue.pending.size)
        assertTrue(f.queue.answers.isEmpty())
        assertTrue(f.runtime.responses.isEmpty())
    }

    @Test
    fun `manual native text and choice answers still reach the captured permission`() = runTest {
        val f = NativeFixture(this)
        for ((input, answer) in nativeInputs) {
            val permission = nativePermission.copy(input = input)
            f.show(permission)
            runCurrent()
            f.queue.send(QuestionnaireIntent.Public.Answer(f.queue.pending.single().id, answer))
            runCurrent()
            val expected = when (answer) {
                is Answer.Text -> StudioPermissionAnswer.Text(answer.value)
                is Answer.Selected -> StudioPermissionAnswer.Selected(answer.ids)
                else -> error("Unexpected test answer")
            }
            assertEquals(permission to expected, f.runtime.responses.last())
            assertTrue(f.queue.pending.isEmpty())
            assertTrue(f.runtime.state.value.permissions.isEmpty())
        }
        assertEquals(nativeInputs.size, f.runtime.responses.size)
        assertEquals(0, f.runtime.modelCalls)
        assertEquals(0, f.modelSettingsRequests)
    }

    @Test
    fun `stale automatic context leaves native question available for a manual answer`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        val question = f.queue.pending.single()
        val generation = (f.queue.state.value as QuestionnaireState.Asking).generations.getValue(question.id)
        f.queue.send(
            QuestionnaireIntent.Public.Answer(
                question.id,
                Answer.Text("automatic approval"),
                NativeAutopilotAnswerContext(nativePermission, generation, { true }) { true },
                expectedGeneration = generation,
            ),
        )
        runCurrent()
        assertTrue(f.runtime.responses.isEmpty())
        assertEquals(listOf(question), f.queue.pending)
        assertTrue((f.queue.state.value as QuestionnaireState.Asking).submitting.isEmpty())
        f.queue.send(QuestionnaireIntent.Public.Answer(question.id, Answer.Text("human")))
        runCurrent()
        assertEquals(nativePermission, f.runtime.responses.single().first)
        assertEquals(StudioPermissionAnswer.Text("human"), f.runtime.responses.single().second)
        assertTrue(f.queue.pending.isEmpty())
        assertEquals(0, f.runtime.modelCalls)
    }
}

private class NativeFixture(scope: TestScope) {
    val runtime = NativeRuntime()
    val queue = NativeQueue()
    private val registry = NativeRegistry(queue)
    private val toggles = NativeToggles()
    var modelSettingsRequests = 0
        private set
    private val settings = lazy {
        modelSettingsRequests++
        error("Native questions must not acquire model settings")
    }

    init {
        scope.backgroundScope.launch {
            StudioQuestionBridge(lazyOf(runtime), registry, toggles, settings).run()
        }
        // This entry point deliberately has no IsolatedPrompts dependency: no native input may reach a model.
        scope.backgroundScope.launch { NativeQuestionnaireAutopilot().run() }
    }

    fun show(permission: StudioPermission = nativePermission) {
        runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
    }
}

private class NativeToggles : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // Both observed settings are Boolean flags.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = when (toggle) {
        AutoContinueEnabled, QuestionnaireEnabled -> MutableStateFlow(true)
        else -> error("Unexpected toggle")
    } as Flow<T>
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

private class NativeRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())
    val responses = mutableListOf<Pair<StudioPermission, StudioPermissionAnswer?>>()
    var modelCalls = 0
        private set
    override suspend fun defaults(): RunSettings = DefaultRunSettings
    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome {
        modelCalls++
        error("A native answer must never start a follow-up")
    }
    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun respond(permission: StudioPermission, optionId: String, answer: StudioPermissionAnswer?) {
        assertEquals("submit", optionId)
        responses += permission to answer
        state.value = state.value.copy(permissions = state.value.permissions - permission)
    }
    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) = error("The exact native permission must be captured")
}

private class NativeQueue : MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput> {
    override val name = "questionnaire"
    override val state = MutableStateFlow<QuestionnaireState>(QuestionnaireState.Idle)
    override val outputs = MutableSharedFlow<QuestionnaireOutput>(extraBufferCapacity = 32)
    val answers = mutableListOf<QuestionnaireIntent.Public.Answer>()
    val pending: List<Questionnaire> get() = (state.value as? QuestionnaireState.Asking)?.pending.orEmpty()
    override suspend fun send(intent: QuestionnaireIntent.Public): SendResult {
        if (intent is QuestionnaireIntent.Public.Answer) answers += intent
        val resolution = QuestionnaireMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolution.to
        resolution.outputs.forEach { check(outputs.tryEmit(it)) }
        return SendResult.Accepted
    }
}

private class NativeRegistry(private val queue: NativeQueue) : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // This fixture exposes only the real questionnaire contract.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = if (key == QuestionnaireMachineKey) queue as MachineRef<S, P, O> else null
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))
    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key)?.send(intent) ?: SendResult.NotRunning
}

private val nativePermission = StudioPermission(
    "chat",
    "question",
    "Approve this command?",
    listOf(StudioPermissionOption("submit", "Submit")),
    input = StudioPermissionInput.FreeText(null, false),
    turnId = "turn",
)
private val nativeInputs = listOf(
    StudioPermissionInput.FreeText(null, false) to Answer.Text("human"),
    StudioPermissionInput.SingleChoice(listOf(StudioPermissionOption("yes", "Approve")), false) to
        Answer.Selected(listOf("yes")),
    StudioPermissionInput.MultiChoice(listOf(StudioPermissionOption("yes", "Approve")), 0, 1) to
        Answer.Selected(listOf("yes")),
)
