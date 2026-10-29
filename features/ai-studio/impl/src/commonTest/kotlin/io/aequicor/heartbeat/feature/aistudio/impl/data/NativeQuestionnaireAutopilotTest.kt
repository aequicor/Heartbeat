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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstruction
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionInput
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPermissionAnswerOrigin
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioQuestionnaireHumanContext
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.toQuestionnaire
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEnabled
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueSettings
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueView
import io.aequicor.heartbeat.feature.autocontinue.api.ChatAuto
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineSpec
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class NativeQuestionnaireAutopilotTest {
    @Test
    fun `live structured answer uses configured target and existing bridge with authority barrier`() = runTest {
        val f = NativeFixture(this)
        f.view.target(nativeTarget.copy(model = ModelId("continuer")))
        f.show()
        runCurrent()
        assertEquals(ModelId("continuer"), f.prompts.calls.single().target.model)
        f.runtime.revocation = CompletableDeferred()
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertEquals(listOf("chat"), f.runtime.invalidations)
        assertTrue(f.runtime.responses.isEmpty())
        val answer = f.queue.autoAnswers.single()
        assertNotNull(answer.expectedGeneration)
        assertTrue(answer.context is NativeAutopilotAnswerContext)
        assertTrue(f.runtime.origins.single() is StudioPermissionAnswerOrigin.NativeAutomatic)
        f.runtime.revocation.complete(Unit)
        runCurrent()
        assertEquals(TrustedUserInstructions.Incomplete, f.repository.instructions)
        assertEquals(nativePermission, f.runtime.responses.single().first)
        assertEquals(StudioPermissionAnswer.Text("blue"), f.runtime.responses.single().second)
        assertTrue(f.queue.pending.isEmpty())
    }

    @Test
    fun `successive automatic answers preserve human context and leave plain permissions pending`() = runTest {
        val f = NativeFixture(this)
        val plain = nativePermission.copy(requestId = "plain", input = null)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(nativePermission, plain))
        runCurrent()
        val plainQuestion = assertNotNull(plain.toQuestionnaire())
        val plainGeneration = (f.queue.state.value as QuestionnaireState.Asking).generations[plainQuestion.id]
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertEquals(TrustedUserInstructions.Incomplete, f.repository.instructions)
        assertEquals(nativeHumanContext, f.repository.humanContext)
        assertEquals(listOf(plain), f.runtime.state.value.permissions)
        assertEquals(listOf(plainQuestion), f.queue.pending)

        val next = nativePermission.copy(requestId = "next-question")
        f.runtime.state.value = f.runtime.state.value.copy(permissions = listOf(plain, next))
        runCurrent()
        assertEquals(2, f.prompts.calls.size)
        assertTrue(f.prompts.calls.last().text.contains("Original human task"))
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertEquals(listOf(nativePermission, next), f.runtime.responses.map { it.first })
        assertEquals(listOf("chat", "chat"), f.runtime.invalidations)
        assertEquals(TrustedUserInstructions.Incomplete, f.repository.instructions)
        assertEquals(nativeHumanContext, f.repository.humanContext)
        assertEquals(listOf(plain), f.runtime.state.value.permissions)
        assertEquals(listOf(plainQuestion), f.queue.pending)
        val queue = f.queue.state.value as QuestionnaireState.Asking
        assertEquals(plainGeneration, queue.generations[plainQuestion.id])
        assertTrue(queue.submitting.isEmpty())
    }

    @Test
    fun `action authority revocation during generation preserves the answer context`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        f.repository.instructions = TrustedUserInstructions.Incomplete
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertEquals(nativePermission, f.runtime.responses.single().first)
        assertEquals(nativeHumanContext, f.repository.humanContext)
        assertEquals(TrustedUserInstructions.Incomplete, f.repository.instructions)
        assertTrue(f.queue.pending.isEmpty())
    }

    @Test
    fun `plain permissions missing native turns and restored questions never reach model`() = runTest {
        val f = NativeFixture(this)
        f.show(nativePermission.copy(input = null))
        runCurrent()
        f.show(nativePermission.copy(turnId = null))
        runCurrent()
        f.runtime.state.value = StudioRuntimeState()
        runCurrent()
        f.queue.send(QuestionnaireIntent.Public.Ask(assertNotNull(nativePermission.toQuestionnaire())))
        runCurrent()
        assertTrue(f.prompts.calls.isEmpty())
        assertTrue(f.queue.autoAnswers.isEmpty())
    }

    @Test
    fun `queue must contain the exact native question`() = runTest {
        val f = NativeFixture(this, bridge = false)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(nativePermission))
        f.queue.send(
            QuestionnaireIntent.Public.Ask(assertNotNull(nativePermission.toQuestionnaire()).copy(title = "stale")),
        )
        runCurrent()
        assertTrue(f.prompts.calls.isEmpty())
        f.queue.send(QuestionnaireIntent.Public.Ask(assertNotNull(nativePermission.toQuestionnaire())))
        runCurrent()
        assertEquals(1, f.prompts.calls.size)
    }

    @Test
    fun `human answer cancels model and stays final`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        f.queue.send(QuestionnaireIntent.Public.Answer(f.queue.pending.single().id, Answer.Text("human")))
        runCurrent()
        assertEquals(1, f.prompts.cancelled)
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertEquals(nativePermission, f.runtime.responses.single().first)
        assertEquals(StudioPermissionAnswer.Text("human"), f.runtime.responses.single().second)
        assertEquals(StudioPermissionAnswerOrigin.ManualOrUnknown, f.runtime.origins.single())
        assertTrue(f.queue.autoAnswers.isEmpty())
    }

    @Test
    fun `native turn replacement cancels old answer and only new generation is delivered`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        val replacement = nativePermission.copy(turnId = "next")
        f.show(replacement)
        runCurrent()
        assertEquals(1, f.prompts.cancelled)
        assertEquals(2, f.prompts.calls.size)
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertEquals(replacement, f.runtime.responses.single().first)
        assertEquals(StudioPermissionAnswer.Text("blue"), f.runtime.responses.single().second)
    }

    @Test
    fun `queue generation replacement cancels identical looking attempt`() = runTest {
        val f = NativeFixture(this, bridge = false)
        f.show()
        runCurrent()
        val question = f.queue.pending.single()
        f.queue.send(QuestionnaireIntent.Public.Ask(question))
        runCurrent()
        assertEquals(1, f.prompts.cancelled)
        assertEquals(2, f.prompts.calls.size)
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        val generation = (f.queue.state.value as QuestionnaireState.Asking).generations[question.id]
        assertEquals(generation, f.queue.autoAnswers.single().expectedGeneration)
    }

    @Test
    fun `mode and both toggles cancel attempts and require autopilot`() = runTest {
        val f = NativeFixture(this)
        f.view.mode(AutoContinueMode.Off)
        f.show()
        runCurrent()
        assertTrue(f.prompts.calls.isEmpty())
        f.view.mode(AutoContinueMode.Autopilot)
        runCurrent()
        f.toggles.auto.value = false
        runCurrent()
        assertEquals(1, f.prompts.cancelled)
        f.toggles.auto.value = true
        runCurrent()
        f.toggles.questions.value = false
        runCurrent()
        assertEquals(2, f.prompts.cancelled)
        f.toggles.questions.value = true
        runCurrent()
        f.view.mode(AutoContinueMode.Off)
        runCurrent()
        assertEquals(3, f.prompts.cancelled)
        assertTrue(f.queue.autoAnswers.isEmpty())
    }

    @Test
    fun `stored chat route change cancels request and uses latest settings`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        assertEquals(nativeTarget, f.prompts.calls.single().target)
        val changed = nativeTarget.copy(model = ModelId("new-model"))
        f.model = Json.encodeToString(EngineTarget.serializer(), changed)
        f.repository.workspace.value = StudioWorkspace(
            emptyList(),
            listOf(StudioSession("chat", null, "chat", Instant.DISTANT_PAST, modelId = f.model)),
        )
        runCurrent()
        assertEquals(1, f.prompts.cancelled)
        assertEquals(changed, f.prompts.calls.last().target)
    }

    @Test
    fun `final settings read rejects result when observation has not caught up`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        f.model = Json.encodeToString(EngineTarget.serializer(), nativeTarget.copy(model = ModelId("changed")))
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertTrue(f.queue.autoAnswers.isEmpty())
        assertTrue(f.runtime.responses.isEmpty())
    }

    @Test
    fun `needs user malformed and unavailable results leave the question open without repeated attempts`() = runTest {
        val outcomes = listOf(
            IsolatedAnswer.Answered("""{"status":"needs_user","reason":"Authorization requires the human"}"""),
            IsolatedAnswer.Answered("""{"status":"answered","answer":{"kind":"selected","ids":["unknown"]}}"""),
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.Unsupported),
        )
        for (outcome in outcomes) {
            val f = NativeFixture(this)
            f.show()
            runCurrent()
            f.prompts.answers.send(outcome)
            runCurrent()
            f.runtime.state.value = f.runtime.state.value.copy(stopFailures = setOf("unrelated"))
            runCurrent()
            assertEquals(1, f.prompts.calls.size)
            assertEquals(1, f.queue.pending.size)
            assertTrue(f.runtime.responses.isEmpty())
            f.runner.cancel()
            f.bridgeRunner?.cancel()
        }
    }

    @Test
    fun `only durable originals supply context regardless of transcript roles or missing marks`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        val prompt = f.prompts.calls.single().text
        assertTrue(prompt.contains("Original human task"))
        assertTrue(prompt.contains("Agent reply"))
        assertFalse(prompt.contains("Transcript human task"))
        assertFalse(prompt.contains("Generated continuation"))
        assertFalse(prompt.contains("Unmarked generated instruction"))
        assertFalse(prompt.contains("host-secret"))
    }

    @Test
    fun `incomplete human context never calls model despite apparent human transcript`() = runTest {
        val f = NativeFixture(this)
        f.repository.humanContext = StudioQuestionnaireHumanContext.Incomplete
        f.show()
        runCurrent()
        assertTrue(f.prompts.calls.isEmpty())
        assertTrue(f.queue.autoAnswers.isEmpty())
        assertEquals(1, f.queue.pending.size)
        assertTrue(f.runtime.invalidations.isEmpty())
    }

    @Test
    fun `human context revoked while assembling prompt prevents model call`() = runTest {
        val f = NativeFixture(this)
        f.repository.beforeMessages = { f.repository.humanContext = StudioQuestionnaireHumanContext.Incomplete }
        f.show()
        runCurrent()
        assertTrue(f.prompts.calls.isEmpty())
        assertTrue(f.queue.autoAnswers.isEmpty())
    }

    @Test
    fun `manual human context revocation during model call discards result`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        assertEquals(1, f.prompts.calls.size)
        val queue = f.queue.state.value
        val permissions = f.runtime.state.value.permissions
        f.repository.humanContext = StudioQuestionnaireHumanContext.Incomplete
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertTrue(f.queue.autoAnswers.isEmpty())
        assertTrue(f.runtime.responses.isEmpty())
        assertTrue(f.runtime.invalidations.isEmpty())
        assertEquals(queue, f.queue.state.value)
        assertEquals(permissions, f.runtime.state.value.permissions)
    }

    @Test
    fun `changed human context identity or text during generation discards result without changing queue`() = runTest {
        val replacements = listOf(
            nativeHumanContext.messages.single().copy(request = RequestId("replacement")),
            nativeHumanContext.messages.single().copy(text = "Changed human task"),
        )
        for (replacement in replacements) {
            val f = NativeFixture(this)
            f.show()
            runCurrent()
            val queue = f.queue.state.value
            val permissions = f.runtime.state.value.permissions
            f.repository.humanContext = nativeHumanContext.copy(messages = listOf(replacement))
            f.prompts.answers.send(nativeAnswer)
            runCurrent()
            assertTrue(f.queue.autoAnswers.isEmpty())
            assertTrue(f.runtime.responses.isEmpty())
            assertTrue(f.runtime.invalidations.isEmpty())
            assertEquals(queue, f.queue.state.value)
            assertEquals(permissions, f.runtime.state.value.permissions)
            f.runner.cancel()
            f.bridgeRunner?.cancel()
        }
    }

    @Test
    fun `human context revocation after queue submission is rejected before bridge revokes authority`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        f.queue.beforeAutoAnswer = { f.repository.humanContext = StudioQuestionnaireHumanContext.Incomplete }
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertEquals(1, f.queue.autoAnswers.size)
        assertTrue(f.runtime.responses.isEmpty())
        assertTrue(f.runtime.invalidations.isEmpty())
        assertEquals(1, f.queue.pending.size)
        assertTrue((f.queue.state.value as QuestionnaireState.Asking).submitting.isEmpty())
        assertEquals(1, f.prompts.calls.size)
    }

    @Test
    fun `human context snapshot is checked after bridge reads current route`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        f.queue.beforeAutoAnswer = {
            f.beforeSettings = { f.repository.humanContext = StudioQuestionnaireHumanContext.Incomplete }
        }
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertEquals(1, f.queue.autoAnswers.size)
        assertTrue(f.runtime.responses.isEmpty())
        assertTrue(f.runtime.invalidations.isEmpty())
        assertEquals(1, f.queue.pending.size)
        assertTrue((f.queue.state.value as QuestionnaireState.Asking).submitting.isEmpty())
    }

    @Test
    fun `profile cancellation stops model and cannot submit late answer`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        f.runner.cancel()
        runCurrent()
        assertEquals(1, f.prompts.cancelled)
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertTrue(f.queue.autoAnswers.isEmpty())
    }

    @Test
    fun `state change during authority revocation prevents native delivery`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        f.runtime.revocation = CompletableDeferred()
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        f.view.mode(AutoContinueMode.Off)
        f.runtime.revocation.complete(Unit)
        runCurrent()
        assertTrue(f.runtime.responses.isEmpty())
        assertEquals(1, f.queue.pending.size)
        assertTrue((f.queue.state.value as QuestionnaireState.Asking).submitting.isEmpty())
    }

    @Test
    fun `manual context revocation during action authority barrier prevents native delivery`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        f.runtime.revocation = CompletableDeferred()
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        assertEquals(1, f.queue.autoAnswers.size)
        f.repository.humanContext = StudioQuestionnaireHumanContext.Incomplete
        f.runtime.revocation.complete(Unit)
        runCurrent()
        assertTrue(f.runtime.responses.isEmpty())
        assertEquals(listOf(nativePermission), f.runtime.state.value.permissions)
        assertEquals(1, f.queue.pending.size)
        assertTrue((f.queue.state.value as QuestionnaireState.Asking).submitting.isEmpty())
        assertEquals(1, f.prompts.calls.size)
    }

    @Test
    fun `replaced generation is not reopened by late failed delivery`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        f.runtime.revocation = CompletableDeferred()
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        val question = f.queue.pending.single()
        f.queue.send(QuestionnaireIntent.Public.Ask(question))
        runCurrent()
        val generation = (f.queue.state.value as QuestionnaireState.Asking).generations[question.id]
        f.runtime.revocation.complete(Unit)
        runCurrent()
        assertEquals(generation, (f.queue.state.value as QuestionnaireState.Asking).generations[question.id])
        assertTrue(f.runtime.responses.isEmpty())
    }

    @Test
    fun `unrelated permission does not restart an active attempt and resolution cancels it`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        val unrelated = nativePermission.copy(requestId = "plain", input = null)
        f.runtime.state.value = f.runtime.state.value.copy(permissions = listOf(nativePermission, unrelated))
        runCurrent()
        assertEquals(1, f.prompts.calls.size)
        assertEquals(0, f.prompts.cancelled)
        f.runtime.state.value = StudioRuntimeState(permissions = listOf(unrelated))
        runCurrent()
        assertEquals(1, f.prompts.cancelled)
        assertTrue(f.queue.autoAnswers.isEmpty())
    }

    @Test
    fun `failed authority barrier reopens for human without automatic retry loop`() = runTest {
        val f = NativeFixture(this)
        f.show()
        runCurrent()
        f.runtime.revocation = CompletableDeferred()
        f.prompts.answers.send(nativeAnswer)
        runCurrent()
        f.runtime.revocation.completeExceptionally(IllegalStateException("Storage unavailable"))
        runCurrent()
        assertEquals(1, f.prompts.calls.size)
        assertEquals(1, f.queue.pending.size)
        assertTrue((f.queue.state.value as QuestionnaireState.Asking).submitting.isEmpty())
        assertTrue(f.runtime.responses.isEmpty())
    }

    @Test
    fun `missing current route leaves question to the human`() = runTest {
        val f = NativeFixture(this)
        f.model = ""
        f.show()
        runCurrent()
        assertTrue(f.prompts.calls.isEmpty())
        assertEquals(1, f.queue.pending.size)
    }

    @Test
    fun `generated provenance cannot authorize plain permission buttons`() = runTest {
        val f = NativeFixture(this)
        val plain = nativePermission.copy(input = null)
        f.show(plain)
        runCurrent()
        f.queue.send(
            QuestionnaireIntent.Public.Answer(
                f.queue.pending.single().id,
                Answer.Selected(listOf("submit")),
                NativeAutopilotAnswerContext(plain, "forged", { true }) { true },
            ),
        )
        runCurrent()
        assertTrue(f.runtime.responses.isEmpty())
        assertTrue(f.runtime.invalidations.isEmpty())
    }
}

private class NativeFixture(scope: TestScope, bridge: Boolean = true) {
    val repository = NativeRepository()
    val runtime = NativeRuntime(repository)
    val queue = NativeQueue()
    val registry = NativeRegistry(queue)
    val view = NativeView()
    val toggles = NativeToggles()
    val prompts = NativePrompts()
    var model = Json.encodeToString(EngineTarget.serializer(), nativeTarget)
    var beforeSettings: () -> Unit = {}
    val settings = StudioChatSettings {
        beforeSettings()
        DefaultRunSettings.copy(modelId = model)
    }
    private val autopilot = NativeQuestionnaireAutopilot(
        lazyOf(runtime),
        registry,
        toggles,
        lazyOf(settings),
        lazyOf(view),
        prompts,
        lazyOf(repository),
    )
    val bridgeRunner = if (bridge) {
        scope.backgroundScope.launch {
            StudioQuestionBridge(lazyOf(runtime), registry, toggles, lazyOf(settings)).run()
        }
    } else {
        null
    }
    val runner = scope.backgroundScope.launch { autopilot.run() }

    suspend fun show(permission: StudioPermission = nativePermission) {
        runtime.state.value = StudioRuntimeState(permissions = listOf(permission))
        if (bridgeRunner == null) {
            queue.send(QuestionnaireIntent.Public.Ask(assertNotNull(permission.toQuestionnaire())))
        }
    }
}

private class NativeView : AutoContinueView {
    override val state = MutableStateFlow<AutoContinueState>(
        AutoContinueState.Ready(chats = mapOf("chat" to ChatAuto(AutoContinueMode.Autopilot))),
    )
    fun mode(mode: AutoContinueMode) {
        state.value = (state.value as AutoContinueState.Ready).copy(chats = mapOf("chat" to ChatAuto(mode)))
    }
    fun target(target: EngineTarget) {
        state.value = (state.value as AutoContinueState.Ready).copy(
            settings = AutoContinueSettings(continuerTarget = target),
        )
    }
}

private class NativePrompts : IsolatedPrompts {
    val calls = mutableListOf<IsolatedPrompt>()
    val answers = Channel<IsolatedAnswer>(Channel.UNLIMITED)
    var cancelled = 0
    override suspend fun ask(prompt: IsolatedPrompt): IsolatedAnswer {
        calls += prompt
        return try {
            answers.receive()
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
    }
}

private class NativeToggles : FeatureToggles {
    val auto = MutableStateFlow(true)
    val questions = MutableStateFlow(true)

    @Suppress("UNCHECKED_CAST") // Both observed settings are Boolean flags.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = when (toggle) {
        AutoContinueEnabled -> auto
        QuestionnaireEnabled -> questions
        else -> error("Unexpected toggle")
    } as Flow<T>
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

private class NativeRuntime(private val repository: NativeRepository) : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())
    val responses = mutableListOf<Pair<StudioPermission, StudioPermissionAnswer?>>()
    val invalidations = mutableListOf<String>()
    val origins = mutableListOf<StudioPermissionAnswerOrigin>()
    var revocation = CompletableDeferred(Unit)
    override suspend fun defaults(): RunSettings = DefaultRunSettings
    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome =
        error("A native answer must never start a follow-up")
    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun invalidateUserInstructions(sessionId: String) {
        repository.humanContext = StudioQuestionnaireHumanContext.Incomplete
        revokeActionAuthority(sessionId)
    }
    private suspend fun revokeActionAuthority(sessionId: String) {
        invalidations += sessionId
        revocation.await()
        repository.instructions = TrustedUserInstructions.Incomplete
    }
    override suspend fun respond(permission: StudioPermission, optionId: String, answer: StudioPermissionAnswer?) {
        origins += StudioPermissionAnswerOrigin.ManualOrUnknown
        deliver(permission, optionId, answer)
    }
    override suspend fun respond(
        permission: StudioPermission,
        optionId: String,
        answer: StudioPermissionAnswer?,
        origin: StudioPermissionAnswerOrigin,
    ) {
        origins += origin
        val automatic = origin as StudioPermissionAnswerOrigin.NativeAutomatic
        check(permission == automatic.permission && automatic.isCurrent() && automatic.isAuthorityCurrent())
        revokeActionAuthority(permission.sessionId)
        check(automatic.isCurrent())
        deliver(permission, optionId, answer)
    }
    private fun deliver(permission: StudioPermission, optionId: String, answer: StudioPermissionAnswer?) {
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
    val autoAnswers = mutableListOf<QuestionnaireIntent.Public.Answer>()
    var beforeAutoAnswer: () -> Unit = {}
    val pending: List<Questionnaire> get() = (state.value as? QuestionnaireState.Asking)?.pending.orEmpty()
    override suspend fun send(intent: QuestionnaireIntent.Public): SendResult {
        if (intent is QuestionnaireIntent.Public.Answer && intent.context is NativeAutopilotAnswerContext) {
            autoAnswers += intent
            beforeAutoAnswer()
        }
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

private class NativeRepository : StudioRepository {
    val workspace = MutableStateFlow(StudioWorkspace(emptyList(), emptyList()))
    override fun observeWorkspace(): Flow<StudioWorkspace> = workspace
    var instructions: TrustedUserInstructions = nativeInstructions
    var humanContext: StudioQuestionnaireHumanContext = nativeHumanContext
    var beforeMessages: () -> Unit = {}
    override suspend fun userInstructions(sessionId: String): TrustedUserInstructions = instructions
    override suspend fun questionnaireHumanContext(sessionId: String): StudioQuestionnaireHumanContext = humanContext
    override fun observeMessages(sessionId: String): Flow<List<StudioMessage>> {
        beforeMessages()
        return flowOf(
            listOf(
                StudioMessage.Prompt(
                    "human",
                    Instant.DISTANT_PAST,
                    "Transcript human task\n\n<heartbeat-directive>host-secret</heartbeat-directive>",
                ),
                StudioMessage.Prompt("auto", Instant.DISTANT_PAST, "Generated continuation", turn = "auto"),
                StudioMessage.Prompt("unmarked", Instant.DISTANT_PAST, "Unmarked generated instruction"),
                StudioMessage.Reply(
                    "reply",
                    Instant.DISTANT_PAST,
                    "Agent reply",
                ),
            ),
        )
    }
    override suspend fun defaultProjectId(): String? = error("Not used")
    override suspend fun append(sessionId: String, message: StudioMessage): Unit = error("Not used")
    override suspend fun replace(sessionId: String, message: StudioMessage): Unit = error("Not used")
    override suspend fun edit(sessionId: String, edit: SessionEdit): Unit = error("Not used")
    override suspend fun setBranch(sessionId: String, branch: String): Unit = error("Not used")
    override fun newMessageId(): String = error("Not used")
}

private val nativeTarget = EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("chat-model"))
private val nativePermission = StudioPermission(
    "chat",
    "question",
    "Which color?",
    listOf(StudioPermissionOption("submit", "Submit")),
    input = StudioPermissionInput.FreeText(null, false),
    turnId = "turn",
)
private val nativeAnswer = IsolatedAnswer.Answered("""{"status":"answered","answer":{"kind":"text","value":"blue"}}""")

private val nativeInstructions = TrustedUserInstructions.Complete(
    listOf(TrustedUserInstruction(RequestId("original"), "Original human task")),
)

private val nativeHumanContext = StudioQuestionnaireHumanContext.Complete(
    listOf(StudioQuestionnaireHumanContext.Message(RequestId("original"), "Original human task")),
)
