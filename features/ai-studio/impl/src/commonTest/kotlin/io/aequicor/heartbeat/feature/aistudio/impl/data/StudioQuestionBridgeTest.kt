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
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
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
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StudioQuestionBridgeTest {
    private val permission = StudioPermission(
        "s1",
        "r1",
        "Run tests?",
        listOf(StudioPermissionOption("allow", "Allow"), StudioPermissionOption("deny", "Deny")),
    )
    private val question = assertNotNull(permission.toQuestionnaire())

    @Test
    fun `live permission is answered through the studio machine and withdrawn once delivered`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        runCurrent()
        assertEquals(listOf<QuestionnaireIntent>(QuestionnaireIntent.Public.Ask(question)), fixture.queue.sent)

        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(true)))
        runCurrent()
        assertEquals(
            listOf<AiStudioIntent.Public>(AiStudioIntent.Public.RespondPermission("s1", "r1", "allow", null)),
            fixture.studio.sent,
        )
        assertEquals(QuestionnaireIntent.Public.Withdraw(question.id), fixture.queue.sent.last())

        // The engine resolving the answered request withdraws nothing twice.
        fixture.runtime.state.value = StudioRuntimeState()
        runCurrent()
        assertEquals(2, fixture.queue.sent.size)
    }

    @Test
    fun `engine resolving an unanswered request withdraws its question`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        runCurrent()
        fixture.runtime.state.value = StudioRuntimeState()
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Withdraw(question.id), fixture.queue.sent.last())
    }

    @Test
    fun `restored questions are answered by a follow-up message and skipped ones are only withdrawn`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState()
        runCurrent()
        assertTrue(fixture.queue.sent.isEmpty())

        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(false)))
        runCurrent()
        assertEquals(
            listOf<AiStudioIntent.Public>(AiStudioIntent.Public.FollowUp("s1", "Run tests?\nDeny")),
            fixture.studio.sent,
        )
        assertEquals(listOf<QuestionnaireIntent>(QuestionnaireIntent.Public.Withdraw(question.id)), fixture.queue.sent)

        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Skipped))
        runCurrent()
        assertEquals(1, fixture.studio.sent.size)
        assertEquals(QuestionnaireIntent.Public.Withdraw(question.id), fixture.queue.sent.last())
    }

    @Test
    fun `undeliverable answers reopen their question and the bridge keeps running`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        runCurrent()
        fixture.studio.result = SendResult.NotRunning
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(true)))
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Ask(question), fixture.queue.sent.last())

        fixture.studio.failure = IllegalArgumentException("studio failed")
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(true)))
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Ask(question), fixture.queue.sent.last())

        fixture.studio.failure = null
        fixture.studio.result = SendResult.Accepted
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(false)))
        runCurrent()
        assertEquals(
            AiStudioIntent.Public.RespondPermission("s1", "r1", "deny", null),
            fixture.studio.sent.last(),
        )
        // A live request is never answered by a duplicate follow-up message.
        assertTrue(fixture.studio.sent.none { it is AiStudioIntent.Public.FollowUp })
    }

    @Test
    fun `an accepted answer that fails to reach the engine reopens its question`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        runCurrent()
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(true)))
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Withdraw(question.id), fixture.queue.sent.last())

        fixture.studio.outputs.emit(AiStudioOutput.PermissionAnswerFailed("s1", "r1"))
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Ask(question), fixture.queue.sent.last())

        // The reopened question can be answered again.
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(false)))
        runCurrent()
        assertEquals(AiStudioIntent.Public.RespondPermission("s1", "r1", "deny", null), fixture.studio.sent.last())
    }

    @Test
    fun `a failed follow-up run reopens its question and a finished one does not`() = runTest {
        val fixture = Fixture(this)
        runCurrent()
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(true)))
        runCurrent()
        fixture.studio.outputs.emit(AiStudioOutput.RunEnded("s1", RunOutcome.Failed))
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Ask(question), fixture.queue.sent.last())

        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(true)))
        runCurrent()
        fixture.studio.outputs.emit(AiStudioOutput.RunEnded("s1", RunOutcome.Completed))
        runCurrent()
        // A later unrelated failure of the session reopens nothing.
        fixture.studio.outputs.emit(AiStudioOutput.RunEnded("s1", RunOutcome.Failed))
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Withdraw(question.id), fixture.queue.sent.last())
    }

    @Test
    fun `an answer failing before the send returns still reopens its question`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        runCurrent()
        fixture.studio.onSend = { fixture.studio.outputs.emit(AiStudioOutput.PermissionAnswerFailed("s1", "r1")) }
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(true)))
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Ask(question), fixture.queue.sent.last())
    }

    @Test
    fun `a second follow-up of a running session never drops the first one`() = runTest {
        val fixture = Fixture(this)
        runCurrent()
        val second = question.copy(id = QuestionnaireId("s1/r2"))
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(question, Answer.Confirmed(true)))
        runCurrent()
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(second, Answer.Confirmed(true)))
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Ask(second), fixture.queue.sent.last())

        fixture.studio.outputs.emit(AiStudioOutput.RunEnded("s1", RunOutcome.Failed))
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Ask(question), fixture.queue.sent.last())
    }

    @Test
    fun `permissions without options are not asked`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState(permissions = listOf(permission.copy(options = emptyList())))
        runCurrent()
        assertTrue(fixture.queue.sent.isEmpty())
    }

    private class Fixture(scope: TestScope) {
        val runtime = FakeRuntime()
        val queue = FakeQueue()
        val studio = FakeStudio()
        val bridge = StudioQuestionBridge(lazyOf(runtime), registry(queue, studio), EnabledToggles)

        init {
            scope.backgroundScope.launch { bridge.run() }
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
}

private class FakeStudio : MachineRef<AiStudioState, AiStudioIntent.Public, AiStudioOutput> {
    override val name = "aistudio"
    override val state = MutableStateFlow<AiStudioState>(AiStudioState.Idle)
    override val outputs = MutableSharedFlow<AiStudioOutput>()
    val sent = mutableListOf<AiStudioIntent.Public>()
    var result = SendResult.Accepted
    var failure: Exception? = null
    var onSend: suspend () -> Unit = {}

    override suspend fun send(intent: AiStudioIntent.Public): SendResult {
        failure?.let { throw it }
        sent += intent
        onSend()
        return result
    }
}

private fun registry(queue: FakeQueue, studio: FakeStudio) = object : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // The test registry serves only the questionnaire and studio keys.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O> = (if (key == QuestionnaireMachineKey) queue else studio) as MachineRef<S, P, O>

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key).send(intent)
}
