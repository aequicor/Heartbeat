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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioQuestionContext
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.toQuestionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.LIVE_QUESTION_ID_PREFIX
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireAnswerContext
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StudioQuestionBridgeTest {
    private val permission = StudioPermission(
        "s1",
        "r1",
        "Run tests?",
        listOf(StudioPermissionOption("allow", "Allow"), StudioPermissionOption("deny", "Deny")),
        turnId = "turn",
    )
    private val question = assertNotNull(permission.toQuestionnaire())

    @Test
    fun `restored answers retain browser provenance while native answers have none`() = runTest {
        val f = Fixture(this)
        val environment = object : StudioChatEnvironment {
            override fun bind(session: SessionRef): AutoCloseable = AutoCloseable { }
        }
        f.queue.send(QuestionnaireIntent.Public.Ask(question))
        runCurrent()
        f.answer(question, context = StudioQuestionContext(environment))
        runCurrent()
        f.queue.send(QuestionnaireIntent.Public.Ask(question))
        runCurrent()
        f.answer(question)
        runCurrent()
        assertEquals(listOf<StudioChatEnvironment?>(environment, null), f.runtime.environments)
    }

    @Test
    fun `native answer waits for acknowledgement without an open studio window and rejects duplicates`() = runTest {
        val f = Fixture(this)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        f.runtime.response = CompletableDeferred()
        runCurrent()
        f.answer(question)
        f.answer(question)
        runCurrent()
        assertEquals(listOf("s1/r1/allow"), f.runtime.responses)
        assertTrue(f.queue.pending().contains(question))
        f.runtime.response.complete(Unit)
        runCurrent()
        assertTrue(f.queue.pending().isEmpty())
        f.runtime.state.value = StudioRuntimeState()
        runCurrent()
        assertEquals(1, f.queue.sent.count { it is QuestionnaireIntent.Public.Withdraw })
    }

    @Test
    fun `late native failure reopens the question and permits another answer`() = runTest {
        val f = Fixture(this)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        f.runtime.response = CompletableDeferred()
        runCurrent()
        f.answer(question)
        runCurrent()
        f.runtime.loseNativeConnection()
        runCurrent()
        f.runtime.response.completeExceptionally(IllegalStateException("native delivery failed"))
        runCurrent()
        assertEquals(QuestionnaireIntent.Public.Ask(question), f.queue.sent.last())
        f.runtime.response = CompletableDeferred(Unit)
        f.answer(question, Answer.Confirmed(false))
        runCurrent()
        assertEquals(listOf("s1/r1/allow", "s1/r1/deny"), f.runtime.responses)
        assertTrue(f.runtime.prompts.isEmpty())
        assertTrue(f.queue.pending().isEmpty())
    }

    @Test
    fun `expired live request never becomes a restored follow-up`() = runTest {
        val f = Fixture(this)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        runCurrent()
        f.answer(question)
        f.runtime.state.value = StudioRuntimeState()
        runCurrent()
        assertTrue(f.runtime.prompts.isEmpty())
        assertTrue(f.queue.pending().isEmpty())
    }

    @Test
    fun `restored follow-up uses chat settings and withdraws on acceptance before completion`() = runTest {
        val f = Fixture(this)
        f.runtime.outcome = CompletableDeferred()
        runCurrent()
        f.queue.send(QuestionnaireIntent.Public.Ask(question))
        f.answer(question, Answer.Confirmed(false))
        runCurrent()
        assertEquals(listOf("s1: Run tests?\nDeny"), f.runtime.prompts)
        assertEquals(listOf("s1"), f.settingsReads)
        assertTrue(f.queue.pending().isEmpty())
        f.runtime.outcome.complete(RunOutcome.Failed)
        runCurrent()
        assertEquals(listOf(question), f.queue.pending())
        f.runtime.outcome = CompletableDeferred(RunOutcome.Completed)
        f.answer(question)
        runCurrent()
        assertTrue(f.queue.pending().isEmpty())
    }

    @Test
    fun `second follow-up cannot displace the running delivery and another session can answer meanwhile`() = runTest {
        val f = Fixture(this)
        f.runtime.outcome = CompletableDeferred()
        runCurrent()
        f.queue.send(QuestionnaireIntent.Public.Ask(question))
        f.answer(question)
        runCurrent()
        val second = question.copy(id = QuestionnaireId("s1/second"))
        f.queue.send(QuestionnaireIntent.Public.Ask(second))
        f.answer(second)
        runCurrent()
        assertEquals(1, f.runtime.prompts.size)
        assertEquals(listOf(second), f.queue.pending())
        val live = permission.copy(sessionId = "s2")
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(live))
        runCurrent()
        f.answer(assertNotNull(live.toQuestionnaire()))
        runCurrent()
        assertEquals(listOf("s2/r1/allow"), f.runtime.responses)
        f.runtime.outcome.complete(RunOutcome.Failed)
        runCurrent()
        assertEquals(setOf(question, second), f.queue.pending().toSet())
    }

    @Test
    fun `old failed follow-up cannot overwrite a replacement even with identical content`() = runTest {
        val f = Fixture(this)
        f.runtime.outcome = CompletableDeferred()
        runCurrent()
        f.queue.send(QuestionnaireIntent.Public.Ask(question))
        f.answer(question)
        runCurrent()
        f.queue.send(QuestionnaireIntent.Public.Ask(question))
        val writes = f.queue.sent.size
        f.runtime.outcome.complete(RunOutcome.Failed)
        runCurrent()
        assertEquals(writes, f.queue.sent.size)
        assertEquals(listOf(question), f.queue.pending())
    }

    @Test
    fun `old native failure does not replace a changed request`() = runTest {
        val f = Fixture(this)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        f.runtime.response = CompletableDeferred()
        runCurrent()
        f.answer(question)
        runCurrent()
        val replacement = permission.copy(title = "Different action?")
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(replacement))
        runCurrent()
        f.runtime.response.completeExceptionally(IllegalStateException("old request failed"))
        runCurrent()
        assertEquals(listOf(replacement.toQuestionnaire()), f.queue.pending())
    }

    @Test
    fun `a legacy question restored after runtime sync is replaced by its turn-aware identity`() = runTest {
        val f = Fixture(this)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        runCurrent()
        val legacy = assertNotNull(permission.copy(turnId = null).toQuestionnaire())
        f.queue.send(QuestionnaireIntent.Public.Ask(legacy))
        val priorHandle = assertNotNull(permission.copy(turnId = "previous-handle-local-id").toQuestionnaire())
        f.queue.send(QuestionnaireIntent.Public.Ask(priorHandle))
        runCurrent()
        assertEquals(listOf(question), f.queue.pending())
        f.queue.outputs.emit(QuestionnaireOutput.Answered(legacy, Answer.Confirmed(true)))
        runCurrent()
        assertTrue(f.runtime.responses.isEmpty())
    }

    @Test
    fun `an old output cannot answer an equal replacement from another turn`() = runTest {
        val f = Fixture(this)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        runCurrent()
        val original = f.queue.pending().single()
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission.copy(turnId = "next")))
        runCurrent()
        assertNotEquals(original.id, f.queue.pending().single().id)
        assertEquals(original.title, f.queue.pending().single().title)
        f.queue.outputs.emit(QuestionnaireOutput.Answered(original, Answer.Confirmed(true)))
        runCurrent()
        assertTrue(f.runtime.responses.isEmpty())
        assertEquals(listOf(permission.copy(turnId = "next").toQuestionnaire()), f.queue.pending())
    }

    @Test
    fun `old successful answer does not withdraw an identical request from the next native turn`() = runTest {
        val f = Fixture(this)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        f.runtime.response = CompletableDeferred()
        runCurrent()
        f.answer(question)
        runCurrent()
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission.copy(turnId = "next")))
        runCurrent()
        f.runtime.response.complete(Unit)
        runCurrent()
        assertEquals(listOf(permission.copy(turnId = "next").toQuestionnaire()), f.queue.pending())
        assertFalse(question.id in (f.queue.state.value as QuestionnaireState.Asking).submitting)
    }

    @Test
    fun `answering a replacement while the previous delivery runs permits retry`() = runTest {
        val f = Fixture(this)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        f.runtime.response = CompletableDeferred()
        runCurrent()
        f.answer(question)
        runCurrent()
        val replacement = permission.copy(title = "Another question")
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(replacement))
        runCurrent()
        val next = assertNotNull(replacement.toQuestionnaire())
        f.answer(next)
        runCurrent()
        assertFalse(next.id in (f.queue.state.value as QuestionnaireState.Asking).submitting)
        f.runtime.response.completeExceptionally(IllegalStateException("old delivery failed"))
        runCurrent()
        f.runtime.response = CompletableDeferred(Unit)
        f.answer(next)
        runCurrent()
        assertTrue(f.queue.pending().isEmpty())
        assertEquals(2, f.runtime.responses.size)
    }

    @Test
    fun `closing the profile cancels an in-flight delivery without reopening questions`() = runTest {
        val f = Fixture(this)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        f.runtime.response = CompletableDeferred()
        runCurrent()
        f.answer(question)
        runCurrent()
        val writes = f.queue.sent.size
        f.runner.cancel()
        runCurrent()
        assertEquals(0, f.queue.outputs.subscriptionCount.value)
        assertEquals(writes, f.queue.sent.size)
    }

    @Test
    fun `skips are withdrawn without running and live ask-user answers retain their owner`() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.queue.send(QuestionnaireIntent.Public.Ask(question))
        f.answer(question, Answer.Skipped)
        runCurrent()
        assertTrue(f.queue.pending().isEmpty())
        val live = question.copy(id = QuestionnaireId("${LIVE_QUESTION_ID_PREFIX}s1/plan/q"))
        f.queue.send(QuestionnaireIntent.Public.Ask(live))
        f.answer(live)
        runCurrent()
        assertEquals(listOf(live), f.queue.pending())
        assertTrue(f.runtime.prompts.isEmpty())
        assertTrue(f.runtime.responses.isEmpty())
    }

    private class Fixture(scope: TestScope) {
        val runtime = FakeQuestionRuntime()
        val queue = FakeQuestionQueue()
        val settingsReads = mutableListOf<String>()
        val bridge = StudioQuestionBridge(
            lazyOf(runtime),
            registry(queue),
            EnabledQuestionToggles,
            lazyOf(
                StudioChatSettings {
                    settingsReads += it
                    DefaultRunSettings
                },
            ),
        )
        val runner = scope.backgroundScope.launch { bridge.run() }
        suspend fun answer(
            question: Questionnaire,
            answer: Answer = Answer.Confirmed(
                true,
            ),
            context: QuestionnaireAnswerContext? = null,
        ) {
            val current = queue.state.value as? QuestionnaireState.Asking
            if (current != null) queue.state.value = current.copy(submitting = current.submitting + question.id)
            val owned = queue.pending().firstOrNull { it.id == question.id } ?: question
            queue.outputs.emit(QuestionnaireOutput.Answered(owned, answer, context))
        }
    }
}

private object EnabledQuestionToggles : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // Every observed toggle is a flag.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(true as T)

    @Suppress("UNCHECKED_CAST") // Every requested toggle is a flag.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = true as T
}

private class FakeQuestionRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())
    val responses = mutableListOf<String>()
    val prompts = mutableListOf<String>()
    val environments = mutableListOf<StudioChatEnvironment?>()
    var response = CompletableDeferred(Unit)
    var outcome = CompletableDeferred(RunOutcome.Completed)
    fun loseNativeConnection() {
        val turn = Turn(TurnId("turn"), null, EngineTarget(EngineId("e"), EngineBindingId("b"), ModelId("m")))
        val unavailable = ActiveSessionState.Unavailable(EngineFailure.Unknown(), activeTurn = turn)
        state.value = state.value.copy(
            permissions = projectStudioPermissions("s1", unavailable, state.value.permissions),
        )
    }
    override suspend fun defaults(): RunSettings = DefaultRunSettings
    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome = error(
        "use callback",
    )
    override suspend fun run(
        sessionId: String,
        prompt: String,
        settings: RunSettings,
        attachments: List<ResourceRef>,
        submissionId: String,
        environment: StudioChatEnvironment?,
        onAccepted: suspend () -> Unit,
    ): RunOutcome {
        environments += environment
        prompts += "$sessionId: $prompt"
        onAccepted()
        return outcome.await()
    }
    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun respond(permission: StudioPermission, optionId: String, answer: StudioPermissionAnswer?) {
        respond(permission.sessionId, permission.requestId, optionId, answer)
    }
    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) {
        responses += "$sessionId/$requestId/$optionId"
        response.await()
    }
}

private class FakeQuestionQueue : MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput> {
    override val name = "questionnaire"
    override val state = MutableStateFlow<QuestionnaireState>(QuestionnaireState.Idle)
    override val outputs = MutableSharedFlow<QuestionnaireOutput>()
    val sent = mutableListOf<QuestionnaireIntent>()
    fun pending() = (state.value as? QuestionnaireState.Asking)?.pending.orEmpty()
    override suspend fun send(intent: QuestionnaireIntent.Public): SendResult {
        sent += intent
        val next = when (intent) {
            is QuestionnaireIntent.Public.Ask -> pending().filterNot { it.id == intent.questionnaire.id } +
                intent.questionnaire

            is QuestionnaireIntent.Public.Withdraw -> pending().filterNot { it.id == intent.id }

            is QuestionnaireIntent.Public.Answer -> error("test emits answers directly")
        }
        state.value = if (next.isEmpty()) QuestionnaireState.Idle else QuestionnaireState.Asking(next)
        return SendResult.Accepted
    }
}

private fun registry(queue: FakeQuestionQueue) = object : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // There is deliberately no studio machine in this profile.
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
