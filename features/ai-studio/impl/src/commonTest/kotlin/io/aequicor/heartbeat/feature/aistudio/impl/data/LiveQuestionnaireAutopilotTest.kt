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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioChatResolver
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioQuestionnaireHumanContext
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioReplyPart
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEnabled
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueSettings
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueView
import io.aequicor.heartbeat.feature.autocontinue.api.ChatAuto
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireAnswerOrigin
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineKey
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineSpec
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LiveQuestionnaireAutopilotTest {
    @Test
    fun `live answer uses configured model and preserves human authority revocation`() = runTest {
        val fixture = LiveFixture()
        fixture.revocation = CompletableDeferred()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        assertEquals(fixture.configuredTarget, fixture.prompts.calls.single().target)
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        assertFalse(call.isCompleted)
        assertEquals(1, fixture.invalidations)
        fixture.revocation.complete(Unit)
        val result = Json.parseToJsonElement(call.await().text).jsonObject
        assertEquals(listOf("q"), result.getValue("autopilotAnswered").jsonArray.map { it.jsonPrimitive.content })
        val answer = result.getValue("answers").jsonObject.getValue("q").jsonObject
        assertEquals("automatic", answer["text"]?.jsonPrimitive?.content)
        fixture.queue.assertEmpty()
        assertNotNull(fixture.queue.autoAnswers.single().expectedGeneration)
        assertEquals(QuestionnaireAnswerOrigin.Auto, fixture.queue.autoAnswers.single().origin)
    }

    @Test
    fun `sequential automatic questionnaires reuse originals after tool authority is revoked`() = runTest {
        val fixture = LiveFixture()
        val original = fixture.repository.humanContext
        repeat(2) { index ->
            val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
            runCurrent()
            assertEquals(index + 1, fixture.prompts.calls.size)
            fixture.prompts.answers.send(generatedAnswer)
            val result = Json.parseToJsonElement(call.await().text).jsonObject
            assertEquals(listOf("q"), result.getValue("autopilotAnswered").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(TrustedUserInstructions.Incomplete, fixture.repository.instructions)
            assertEquals(original, fixture.repository.humanContext)
        }
        assertEquals(2, fixture.automaticInvalidations)
        assertEquals(2, fixture.invalidations)
    }

    @Test
    fun `manual answer invalidates context and prevents the next automatic questionnaire`() = runTest {
        val fixture = LiveFixture()
        val first = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        fixture.manual()
        assertFalse(first.await().isError)
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, fixture.repository.humanContext)
        assertEquals(0, fixture.automaticInvalidations)
        val next = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        assertEquals(1, fixture.prompts.calls.size)
        assertFalse(next.isCompleted)
        fixture.manual()
        assertFalse(next.await().isError)
        assertEquals(2, fixture.invalidations)
    }

    @Test
    fun `auto plus manual skip preserves context and auto plus manual text revokes it`() = runTest {
        for (manual in listOf(Answer.Skipped, Answer.Text("human"))) {
            val fixture = LiveFixture()
            val original = fixture.repository.humanContext
            val call = async {
                fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments(optional = true, count = 2))
            }
            runCurrent()
            fixture.prompts.answers.send(generatedAnswer)
            runCurrent()
            fixture.queue.send(QuestionnaireIntent.Public.Answer(fixture.queue.pending.single().id, manual))
            val result = Json.parseToJsonElement(call.await().text).jsonObject
            assertEquals(listOf("q"), result.getValue("autopilotAnswered").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(1, fixture.invalidations)
            assertEquals(if (manual == Answer.Skipped) 1 else 0, fixture.automaticInvalidations)
            assertEquals(
                if (manual == Answer.Skipped) original else StudioQuestionnaireHumanContext.Incomplete,
                fixture.repository.humanContext,
            )
        }
    }

    @Test
    fun `tool authority revocation during generation does not invalidate unchanged human context`() = runTest {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        fixture.repository.invalidate(automatic = true)
        fixture.prompts.answers.send(generatedAnswer)
        assertFalse(call.await().isError)
        assertEquals(1, fixture.queue.autoAnswers.size)
        assertEquals(1, fixture.automaticInvalidations)
    }

    @Test
    fun `late automatic revocation cannot restore context invalidated by a concurrent manual answer`() = runTest {
        val fixture = LiveFixture()
        fixture.revocation = CompletableDeferred()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        assertEquals(1, fixture.automaticInvalidations)
        assertFalse(call.isCompleted)
        fixture.repository.invalidate()
        fixture.revocation.complete(Unit)
        assertFalse(call.await().isError)
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, fixture.repository.humanContext)
        val next = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        assertEquals(1, fixture.prompts.calls.size)
        next.cancelAndJoin()
    }

    @Test
    fun `unsupported automatic revocation safely delegates to ordinary revocation`() = runTest {
        val fixture = LiveFixture()
        val revoked = mutableListOf<SessionRef>()
        val resolver = object : StudioChatResolver {
            override suspend fun sessionIdOf(ref: SessionRef): String? = null
            override suspend fun invalidateUserInstructions(ref: SessionRef) {
                revoked += ref
            }
        }
        resolver.invalidateUserInstructionsForAutomaticQuestionnaire(fixture.context.session)
        assertEquals(listOf(fixture.context.session), revoked)
    }

    @Test
    fun `chat target is the fallback and missing target leaves question to human`() = runTest {
        val fixture = LiveFixture(configured = false)
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        assertEquals(fixture.context.target, fixture.prompts.calls.single().target)
        call.cancelAndJoin()
        val missing = async {
            fixture.tools.execute(fixture.context.copy(target = null), "questionnaire_ask", liveArguments())
        }
        runCurrent()
        assertEquals(1, fixture.prompts.calls.size)
        fixture.manual()
        assertFalse(missing.await().isError)
    }

    @Test
    fun `off and suggest do not generate but enabling autopilot starts the live call`() = runTest {
        for (mode in listOf(AutoContinueMode.Off, AutoContinueMode.Suggest)) {
            val fixture = LiveFixture(mode = mode)
            val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
            runCurrent()
            assertTrue(fixture.prompts.calls.isEmpty())
            fixture.mode(AutoContinueMode.Autopilot)
            runCurrent()
            assertEquals(1, fixture.prompts.calls.size)
            call.cancelAndJoin()
        }
    }

    @Test
    fun `manual answer cancels model and is returned without automatic provenance`() = runTest {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        fixture.manual()
        val result = Json.parseToJsonElement(call.await().text).jsonObject
        assertEquals(1, fixture.prompts.cancelled)
        assertTrue(fixture.queue.autoAnswers.isEmpty())
        assertFalse("autopilotAnswered" in result)
    }

    @Test
    fun `manual answer to one question cancels the whole batch attempt`() = runTest {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments(count = 2)) }
        runCurrent()
        fixture.manual(index = 1)
        runCurrent()
        assertEquals(1, fixture.prompts.cancelled)
        assertEquals(1, fixture.prompts.calls.size)
        assertFalse(call.isCompleted)
        fixture.manual()
        assertFalse(call.await().isError)
    }

    @Test
    fun `queue rejects automatic answer when another manual answer wins before collection`() = runTest {
        for (alreadyAnswered in 0..1) {
            val fixture = LiveFixture()
            val call = async {
                fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments(count = 2 + alreadyAnswered))
            }
            runCurrent()
            if (alreadyAnswered == 1) {
                fixture.prompts.answers.send(generatedAnswer)
                runCurrent()
            }
            val pending = fixture.queue.pending
            assertEquals(2, pending.size)
            fixture.queue.beforeAutomaticAnswer = { automatic ->
                assertEquals(pending.map { it.id }.toSet(), automatic.expectedPendingGenerations?.keys)
                assertEquals(
                    SendResult.Accepted,
                    fixture.queue.send(QuestionnaireIntent.Public.Answer(pending.last().id, Answer.Text("human"))),
                )
                // The manual transition has committed but its output collector has not run yet.
                assertFalse((automatic.context as LiveAutopilotAnswerContext).manualAnswer.isCompleted)
            }
            fixture.prompts.answers.send(generatedAnswer)
            runCurrent()

            assertFalse(call.isCompleted)
            assertEquals(listOf(pending.first()), fixture.queue.pending)
            assertTrue((fixture.queue.state.value as QuestionnaireState.Asking).submitting.isEmpty())
            fixture.manual()
            val result = Json.parseToJsonElement(call.await().text).jsonObject
            val automatic = result["autopilotAnswered"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            assertEquals(if (alreadyAnswered == 1) listOf("q") else emptyList(), automatic)
            assertEquals(2, fixture.invalidations)
            assertEquals(0, fixture.automaticInvalidations)
            assertEquals(StudioQuestionnaireHumanContext.Incomplete, fixture.repository.humanContext)
        }
    }

    @Test
    fun `accepted automatic answers stay excluded when autopilot restarts for the remaining batch`() = runTest {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments(count = 3)) }
        runCurrent()
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        fixture.mode(AutoContinueMode.Off)
        runCurrent()
        fixture.mode(AutoContinueMode.Autopilot)
        runCurrent()
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        fixture.prompts.answers.send(generatedAnswer)
        val result = Json.parseToJsonElement(call.await().text).jsonObject

        assertEquals(
            setOf("q", "q1", "q2"),
            result.getValue("autopilotAnswered").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(listOf(3, 2, 1), fixture.queue.autoAnswers.map { it.expectedPendingGenerations?.size })
        fixture.queue.assertEmpty()
    }

    @Test
    fun `mode and target changes cancel obsolete generation`() = runTest {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        fixture.mode(AutoContinueMode.Suggest)
        runCurrent()
        assertEquals(1, fixture.prompts.cancelled)
        assertFalse(call.isCompleted)
        fixture.mode(AutoContinueMode.Autopilot)
        runCurrent()
        val ready = fixture.view.state.value as AutoContinueState.Ready
        fixture.view.state.value = ready.copy(settings = ready.settings.copy(continuerTarget = fixture.context.target))
        runCurrent()
        assertEquals(2, fixture.prompts.cancelled)
        assertEquals(fixture.context.target, fixture.prompts.calls.last().target)
        call.cancelAndJoin()
    }

    @Test
    fun `feature disabling cancels the model and keeps the card answerable`() = runTest {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        fixture.toggles.enabled.value = false
        runCurrent()
        assertEquals(1, fixture.prompts.cancelled)
        assertFalse(call.isCompleted)
        fixture.manual()
        assertFalse(call.await().isError)
    }

    @Test
    fun `disabling questionnaires cancels the model without answering`() = runTest {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        fixture.toggles.questionsEnabled.value = false
        runCurrent()
        assertEquals(1, fixture.prompts.cancelled)
        assertTrue(fixture.queue.autoAnswers.isEmpty())
        call.cancelAndJoin()
    }

    @Test
    fun `call cancellation and native lifetime cancellation stop model work`() = runTest {
        val fixture = LiveFixture()
        val lifetime = Job()
        val call = async {
            fixture.tools.execute(fixture.context.copy(lifetime = lifetime), "questionnaire_ask", liveArguments())
        }
        runCurrent()
        lifetime.cancel()
        runCurrent()
        assertEquals(1, fixture.prompts.cancelled)
        call.cancelAndJoin()
        fixture.queue.assertEmpty()
        assertEquals(0, fixture.queue.outputs.subscriptionCount.value)
        val second = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        second.cancelAndJoin()
        assertEquals(2, fixture.prompts.cancelled)
        fixture.queue.assertEmpty()
    }

    @Test
    fun `optional deadline cancels pending generation and returns explicit timeout`() = runTest {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments(optional = true)) }
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        val result = Json.parseToJsonElement(call.await().text).jsonObject
        assertEquals(listOf("q"), result.getValue("timedOut").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, fixture.prompts.cancelled)
        assertEquals(0, fixture.invalidations)
        assertTrue(fixture.queue.autoAnswers.isEmpty())
    }

    @Test
    fun `abstention invalid output and unavailable model all preserve manual fallback`() = runTest {
        val results = listOf(
            IsolatedAnswer.Answered("""{"status":"needs_user"}"""),
            IsolatedAnswer.Answered("not json"),
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.Incomplete),
        )
        for (result in results) {
            val fixture = LiveFixture()
            val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
            runCurrent()
            fixture.prompts.answers.send(result)
            runCurrent()
            assertFalse(call.isCompleted)
            assertTrue(fixture.queue.autoAnswers.isEmpty())
            fixture.manual()
            assertFalse(call.await().isError)
        }
    }

    @Test
    fun `same key concurrent calls own separate questions and model jobs`() = runTest {
        val fixture = LiveFixture()
        val first = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        val second = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        assertEquals(2, fixture.prompts.calls.size)
        first.cancelAndJoin()
        assertEquals(1, fixture.queue.pending.size)
        assertEquals(1, fixture.prompts.cancelled)
        fixture.prompts.answers.send(generatedAnswer)
        assertFalse(second.await().isError)
        fixture.queue.assertEmpty()
    }

    @Test
    fun `superseded identical question rejects the old generation atomically`() = runTest {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        fixture.queue.send(QuestionnaireIntent.Public.Ask(fixture.queue.pending.single()))
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        assertFalse(call.isCompleted)
        assertTrue((fixture.queue.state.value as QuestionnaireState.Asking).submitting.isEmpty())
        fixture.manual()
        runCurrent()
        assertFalse(call.isCompleted)
        call.cancelAndJoin()
        assertEquals(1, fixture.queue.pending.size)
    }

    @Test
    fun `native permission cards are never read or answered by live autopilot`() = runTest {
        val fixture = LiveFixture()
        val native = Questionnaire(
            QuestionnaireId("native-permission"),
            "chat",
            "Allow command?",
            Question.Confirm("Yes", "No"),
        )
        fixture.queue.send(QuestionnaireIntent.Public.Ask(native))
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        assertFalse(fixture.prompts.calls.single().text.contains("Allow command?"))
        fixture.prompts.answers.send(generatedAnswer)
        assertFalse(call.await().isError)
        assertEquals(listOf(native), fixture.queue.pending)
        assertTrue(fixture.queue.autoAnswers.none { it.id == native.id })
    }

    @Test
    fun `complete originals supply human context without trusting transcript prompts or marks`() = runTest {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        val text = fixture.prompts.calls.single().text
        assertTrue(text.contains("Current human task"))
        assertTrue(text.contains("Never delete files"))
        assertTrue(text.contains("Question follows"))
        assertFalse(text.contains("Transcript human claim"))
        assertFalse(text.contains("Unmarked generated instruction"))
        assertFalse(text.contains("Marked generated instruction"))
        assertFalse(text.contains("\"author\":\"autopilot\""))
        assertFalse(text.contains("Secret reasoning"))
        call.cancelAndJoin()
    }

    @Test
    fun `incomplete human context never calls model and leaves questions to human`() = runTest {
        val fixture = LiveFixture()
        fixture.repository.changeContext(StudioQuestionnaireHumanContext.Incomplete)
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        assertTrue(fixture.prompts.calls.isEmpty())
        assertTrue(fixture.queue.autoAnswers.isEmpty())
        assertFalse(call.isCompleted)
        assertEquals(0, fixture.invalidations)
        fixture.manual()
        val result = Json.parseToJsonElement(call.await().text).jsonObject
        assertFalse("autopilotAnswered" in result)
        assertEquals(1, fixture.invalidations)
    }

    @Test
    fun `unsupported atomic admission never dispatches even with complete human context`() = runTest {
        val fixture = LiveFixture()
        fixture.repository.supportsAdmission = false
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        assertEquals(1, fixture.prompts.calls.size)
        assertTrue(fixture.queue.autoAnswers.isEmpty())
        assertFalse(call.isCompleted)
        fixture.manual()
        assertFalse(call.await().isError)
    }

    @Test
    fun `context change after final read but before atomic admission rejects stale answer`() = runTest {
        val changed = StudioQuestionnaireHumanContext.Complete(
            listOf(StudioQuestionnaireHumanContext.Message(RequestId("new"), "Changed human restriction")),
        )
        for (snapshot in listOf(StudioQuestionnaireHumanContext.Incomplete, changed)) {
            val fixture = LiveFixture()
            val reached = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            fixture.repository.beforeAdmission = {
                reached.complete(Unit)
                proceed.await()
            }
            val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments(count = 2)) }
            runCurrent()
            fixture.prompts.answers.send(generatedAnswer)
            runCurrent()
            assertTrue(reached.isCompleted)
            fixture.repository.changeContext(snapshot)
            proceed.complete(Unit)
            runCurrent()
            assertTrue(fixture.queue.autoAnswers.isEmpty())
            assertEquals(1, fixture.prompts.calls.size)
            assertEquals(2, fixture.queue.pending.size)
            assertFalse(call.isCompleted)
            call.cancelAndJoin()
            fixture.queue.assertEmpty()
        }
    }

    @Test
    fun `context mutation waits until admitted machine send completes`() = runTest {
        val fixture = LiveFixture()
        val reached = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.queue.beforeAutomaticAnswer = {
            reached.complete(Unit)
            proceed.await()
        }
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        // A model awaiting its result does not hold the context mutex.
        val original = fixture.repository.questionnaireHumanContext("chat")
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        assertTrue(reached.isCompleted)
        val mutation = async { fixture.repository.invalidate() }
        runCurrent()
        assertFalse(mutation.isCompleted)
        assertEquals(original, fixture.repository.humanContext)
        proceed.complete(Unit)
        assertFalse(call.await().isError)
        mutation.await()
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, fixture.repository.humanContext)
        assertEquals(1, fixture.queue.autoAnswers.size)
        assertEquals(1, fixture.automaticInvalidations)
    }

    @Test
    fun `completed manual batch invalidates another batch waiting for admission`() = runTest {
        val fixture = LiveFixture()
        val first = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        val second = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        val reached = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.repository.beforeAdmission = {
            reached.complete(Unit)
            proceed.await()
        }
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        assertTrue(reached.isCompleted)
        fixture.manual(index = 1)
        assertFalse(second.await().isError)
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, fixture.repository.humanContext)
        proceed.complete(Unit)
        runCurrent()
        assertTrue(fixture.queue.autoAnswers.isEmpty())
        assertFalse(first.isCompleted)
        fixture.manual()
        assertFalse(first.await().isError)
    }

    @Test
    fun `lifetime ended while admission waits cannot dispatch the generated answer`() = runTest {
        val fixture = LiveFixture()
        val lifetime = Job()
        val callContext = fixture.context.copy(lifetime = lifetime)
        val reached = CompletableDeferred<Unit>()
        fixture.repository.beforeAdmission = {
            reached.complete(Unit)
            // Close the lifetime immediately before the admission callback, without running its monitor.
            lifetime.cancel()
        }
        val call = async { fixture.tools.execute(callContext, "questionnaire_ask", liveArguments()) }
        runCurrent()
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        assertTrue(reached.isCompleted)
        assertTrue(fixture.queue.autoAnswers.isEmpty())
        assertFalse(call.isCompleted)
        call.cancelAndJoin()
        fixture.queue.assertEmpty()
    }

    @Test
    fun `revoked or changed human context during generation discards answer for whole batch`() = runTest {
        val changed = StudioQuestionnaireHumanContext.Complete(
            listOf(StudioQuestionnaireHumanContext.Message(RequestId("new"), "Changed human restriction")),
        )
        for (snapshot in listOf(StudioQuestionnaireHumanContext.Incomplete, changed)) {
            val fixture = LiveFixture()
            val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments(count = 2)) }
            runCurrent()
            assertEquals(1, fixture.prompts.calls.size)
            fixture.repository.changeContext(snapshot)
            fixture.prompts.answers.send(generatedAnswer)
            runCurrent()
            assertTrue(fixture.queue.autoAnswers.isEmpty())
            assertEquals(1, fixture.prompts.calls.size)
            assertEquals(2, fixture.queue.pending.size)
            assertFalse(call.isCompleted)
            assertEquals(0, fixture.invalidations)
            fixture.manual()
            runCurrent()
            fixture.manual()
            val result = Json.parseToJsonElement(call.await().text).jsonObject
            assertFalse("autopilotAnswered" in result)
            assertEquals(2, fixture.invalidations)
        }
    }
}

class LiveQuestionnaireAutopilotManualAnswerTest {
    @Test
    fun `unfinished manual batch invalidates another batch waiting for admission`() = runTest {
        val fixture = LiveFixture()
        val first = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        val second = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments(count = 2)) }
        runCurrent()
        val reached = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.repository.beforeAdmission = {
            reached.complete(Unit)
            proceed.await()
        }
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        assertTrue(reached.isCompleted)
        fixture.manual(index = 1)
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, fixture.repository.humanContext)
        assertEquals(2, fixture.queue.pending.size)
        proceed.complete(Unit)
        runCurrent()
        assertTrue(fixture.queue.autoAnswers.isEmpty())
        assertFalse(first.isCompleted)
        first.cancelAndJoin()
        fixture.manual()
        assertFalse(second.await().isError)
        assertEquals(2, fixture.invalidations)
    }

    @Test
    fun `manual revocation keeps its card and cancels generation before the batch completes`() = runTest {
        val fixture = LiveFixture()
        fixture.revocation = CompletableDeferred()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments(count = 2)) }
        runCurrent()
        fixture.manual()
        runCurrent()
        assertEquals(1, fixture.invalidations)
        assertEquals(1, fixture.prompts.cancelled)
        assertEquals(2, fixture.queue.pending.size)
        assertFalse(call.isCompleted)
        fixture.revocation.complete(Unit)
        runCurrent()
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, fixture.repository.humanContext)
        assertEquals(1, fixture.queue.pending.size)
        assertFalse(call.isCompleted)
        fixture.manual()
        assertFalse(call.await().isError)
        assertEquals(2, fixture.invalidations)
        assertEquals(0, fixture.automaticInvalidations)
    }

    @Test
    fun `machine rejects another batch after manual acceptance before output collection`() = runTest {
        val fixture = LiveFixture()
        val first = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        val second = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments(count = 2)) }
        runCurrent()
        val manual = fixture.queue.pending[1]
        val original = fixture.repository.humanContext
        fixture.queue.beforeAutomaticAnswer = {
            assertEquals(
                SendResult.Accepted,
                fixture.queue.send(QuestionnaireIntent.Public.Answer(manual.id, Answer.Text("new restriction"))),
            )
            // The synchronous machine transition has committed. Its buffered output has not been collected.
            assertEquals(0, fixture.invalidations)
            assertEquals(original, fixture.repository.humanContext)
        }
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        assertEquals(listOf(SendResult.Ignored), fixture.queue.autoAnswerResults)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, fixture.repository.humanContext)
        fixture.manual()
        val result = Json.parseToJsonElement(first.await().text).jsonObject
        assertFalse("autopilotAnswered" in result)
        fixture.manual()
        assertFalse(second.await().isError)
        fixture.queue.assertEmpty()
        assertEquals(0, fixture.queue.outputs.subscriptionCount.value)
    }

    @Test
    fun `manual fence follows source and survives replacement while skip keeps auto admission`() = runTest {
        for (sameSource in listOf(true, false)) {
            for (manualAnswer in listOf(Answer.Text("new restriction"), Answer.Skipped)) {
                for (replaceManual in listOf(false, true)) {
                    checkManualFence(sameSource, manualAnswer, replaceManual)
                }
            }
        }
    }

    private suspend fun TestScope.checkManualFence(sameSource: Boolean, manualAnswer: Answer, replaceManual: Boolean) {
        val fixture = LiveFixture()
        val call = async { fixture.tools.execute(fixture.context, "questionnaire_ask", liveArguments()) }
        runCurrent()
        val manual = fixture.queue.pending.single().copy(
            id = QuestionnaireId("other-batch"),
            source = if (sameSource) "chat" else "other-chat",
            isSkippable = true,
        )
        fixture.queue.send(QuestionnaireIntent.Public.Ask(manual))
        val original = fixture.repository.humanContext
        fixture.queue.beforeAutomaticAnswer = {
            assertEquals(
                SendResult.Accepted,
                fixture.queue.send(QuestionnaireIntent.Public.Answer(manual.id, manualAnswer)),
            )
            if (replaceManual) {
                fixture.queue.send(QuestionnaireIntent.Public.Ask(manual.copy(title = "Replacement")))
            }
            assertEquals(0, fixture.invalidations)
            assertEquals(original, fixture.repository.humanContext)
        }
        fixture.prompts.answers.send(generatedAnswer)
        runCurrent()
        val blocked = sameSource && manualAnswer != Answer.Skipped
        assertEquals(
            listOf(if (blocked) SendResult.Ignored else SendResult.Accepted),
            fixture.queue.autoAnswerResults,
        )
        if (blocked) {
            assertFalse(call.isCompleted)
            call.cancelAndJoin()
        } else {
            val result = Json.parseToJsonElement(call.await().text).jsonObject
            assertEquals(
                listOf("q"),
                result.getValue("autopilotAnswered").jsonArray.map { it.jsonPrimitive.content },
            )
        }
        assertEquals(listOf(manual.id), fixture.queue.pending.map { it.id })
        assertEquals(
            if (replaceManual) "Replacement" else manual.title,
            fixture.queue.pending.single().title,
        )
        assertEquals(0, fixture.queue.outputs.subscriptionCount.value)
    }
}

private class LiveFixture(mode: AutoContinueMode = AutoContinueMode.Autopilot, configured: Boolean = true) {
    val configuredTarget = EngineTarget(EngineId("test"), EngineBindingId("configured"), ModelId("continuer"))
    val context = AgentToolContext(
        SessionRef(EngineId("test"), SessionSourceId("local"), "native"),
        workspace = null,
        turn = TurnId("turn"),
        target = configuredTarget.copy(model = ModelId("chat")),
    )
    val view = LiveView(
        AutoContinueState.Ready(
            settings = AutoContinueSettings(continuerTarget = configuredTarget.takeIf { configured }),
            chats = mapOf("chat" to ChatAuto(mode)),
        ),
    )
    val queue = LiveQueue()
    val toggles = LiveToggles()
    val prompts = LivePrompts()
    val repository = LiveRepository()
    var invalidations = 0
    var automaticInvalidations = 0
    var revocation = CompletableDeferred(Unit)
    private val chats = object : StudioChatResolver {
        override suspend fun sessionIdOf(ref: SessionRef) = "chat"
        override suspend fun invalidateUserInstructions(ref: SessionRef) = invalidate(automatic = false)
        override suspend fun invalidateUserInstructionsForAutomaticQuestionnaire(ref: SessionRef) =
            invalidate(automatic = true)
    }

    private suspend fun invalidate(automatic: Boolean) {
        invalidations++
        if (automatic) automaticInvalidations++
        revocation.await()
        repository.invalidate(automatic)
    }
    val tools = AskUserAgentTools(
        LiveRegistry(queue),
        lazyOf(chats),
        toggles,
        LiveQuestionnaireAutopilot(
            lazyOf(view),
            prompts,
            lazyOf(repository),
            toggles,
        ),
    )

    fun mode(mode: AutoContinueMode) {
        val ready = view.state.value as AutoContinueState.Ready
        view.state.value = ready.copy(chats = mapOf("chat" to ChatAuto(mode)))
    }

    suspend fun manual(index: Int = 0) {
        queue.send(QuestionnaireIntent.Public.Answer(queue.pending[index].id, Answer.Text("human")))
    }
}

private class LiveView(initial: AutoContinueState) : AutoContinueView {
    override val state = MutableStateFlow(initial)
}

private class LivePrompts : IsolatedPrompts {
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

private class LiveToggles : FeatureToggles {
    val enabled = MutableStateFlow(true)
    val questionsEnabled = MutableStateFlow(true)

    @Suppress("UNCHECKED_CAST") // Both tested settings are boolean flags.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> =
        (
            when (toggle) {
                AutoContinueEnabled -> enabled
                QuestionnaireEnabled -> questionsEnabled
                else -> flowOf(true)
            }
        ) as Flow<T>

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

/** Runs the actual pure queue contract, including generation and answer validation, with hot outputs. */
private class LiveQueue : MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput> {
    override val name = "questionnaire"
    override val state = MutableStateFlow<QuestionnaireState>(QuestionnaireState.Idle)
    override val outputs = MutableSharedFlow<QuestionnaireOutput>(extraBufferCapacity = 32)
    val autoAnswers = mutableListOf<QuestionnaireIntent.Public.Answer>()
    val autoAnswerResults = mutableListOf<SendResult>()
    var beforeAutomaticAnswer: (suspend (QuestionnaireIntent.Public.Answer) -> Unit)? = null
    val pending: List<Questionnaire> get() = (state.value as? QuestionnaireState.Asking)?.pending.orEmpty()
    fun assertEmpty() {
        val asking = state.value as? QuestionnaireState.Asking ?: return
        assertTrue(asking.pending.isEmpty())
        assertTrue(asking.submitting.isEmpty())
        assertTrue(asking.generations.isEmpty())
        assertTrue(asking.preparing.isEmpty())
    }

    override suspend fun send(intent: QuestionnaireIntent.Public): SendResult {
        val automatic = intent is QuestionnaireIntent.Public.Answer && intent.context is LiveAutopilotAnswerContext
        if (automatic) {
            val answer = intent as QuestionnaireIntent.Public.Answer
            autoAnswers += answer
            beforeAutomaticAnswer?.invoke(answer)
        }
        val resolution = QuestionnaireMachineSpec.resolve(state.value, intent)
        val result = if (resolution == null) SendResult.Ignored else SendResult.Accepted
        if (automatic) autoAnswerResults += result
        if (resolution != null) {
            state.value = resolution.to
            resolution.outputs.forEach { check(outputs.tryEmit(it)) }
        }
        return result
    }
}

private class LiveRegistry(private val queue: LiveQueue) : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // The registry exposes only the actual questionnaire contract.
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

private class LiveRepository : StudioRepository {
    private val originals = StudioHumanAuthority()
        .append(RequestId("original"), "Never delete files")
        .append(RequestId("current"), "Current human task")
    var instructions: TrustedUserInstructions = originals.snapshot()
    private val contextLock = Mutex()
    var humanContext: StudioQuestionnaireHumanContext = originals.questionnaireContextSnapshot()
        private set
    var beforeAdmission: (suspend () -> Unit)? = null
    var supportsAdmission = true

    suspend fun changeContext(snapshot: StudioQuestionnaireHumanContext) = contextLock.withLock {
        humanContext = snapshot
    }

    suspend fun invalidate(automatic: Boolean = false) = contextLock.withLock {
        instructions = TrustedUserInstructions.Incomplete
        if (!automatic) humanContext = StudioQuestionnaireHumanContext.Incomplete
    }

    override suspend fun userInstructions(sessionId: String): TrustedUserInstructions = instructions
    override suspend fun questionnaireHumanContext(sessionId: String): StudioQuestionnaireHumanContext =
        contextLock.withLock { humanContext }

    override suspend fun admitQuestionnaireAnswer(
        sessionId: String,
        expected: StudioQuestionnaireHumanContext.Complete,
        admit: suspend () -> Boolean,
    ): Boolean {
        if (!supportsAdmission) return super.admitQuestionnaireAnswer(sessionId, expected, admit)
        beforeAdmission?.invoke()
        return contextLock.withLock { humanContext == expected && admit() }
    }
    override fun observeMessages(sessionId: String): Flow<List<StudioMessage>> = flowOf(
        listOf(
            StudioMessage.Prompt("human", Instant.DISTANT_PAST, "Transcript human claim"),
            StudioMessage.Prompt("unmarked", Instant.DISTANT_PAST, "Unmarked generated instruction"),
            StudioMessage.Prompt("generated", Instant.DISTANT_PAST, "Marked generated instruction", turn = "auto"),
            StudioMessage.Reply(
                "reply",
                Instant.DISTANT_PAST,
                "Question follows",
                parts = listOf(StudioReplyPart.Reasoning("reason", "Secret reasoning")),
            ),
        ),
    )
    override fun observeWorkspace(): Flow<StudioWorkspace> = error("Not used")
    override suspend fun defaultProjectId(): String? = error("Not used")
    override suspend fun append(sessionId: String, message: StudioMessage): Unit = error("Not used")
    override suspend fun replace(sessionId: String, message: StudioMessage): Unit = error("Not used")
    override suspend fun edit(sessionId: String, edit: SessionEdit): Unit = error("Not used")
    override suspend fun setBranch(sessionId: String, branch: String): Unit = error("Not used")
    override fun newMessageId(): String = error("Not used")
}

private fun liveArguments(optional: Boolean = false, count: Int = 1) = Json.parseToJsonElement(
    """{"key":"same","questions":[""" + (0 until count).joinToString(",") { index ->
        val id = if (index == 0) "q" else "q$index"
        """{"id":"$id","title":"Q","kind":"FreeText","isOptional":$optional}"""
    } + "]}",
).jsonObject

private val generatedAnswer = IsolatedAnswer.Answered(
    """{"status":"answered","answer":{"kind":"text","value":"automatic"}}""",
)
