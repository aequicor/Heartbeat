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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioChatResolver
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AskUserToolsTest {
    private val context = AgentToolContext(
        SessionRef(EngineId("test"), SessionSourceId("local"), "native"),
        workspace = null,
        turn = TurnId("turn"),
        request = RequestId("request"),
    )
    private val arguments = Json.parseToJsonElement(
        """{"key":"plan","questions":[
            {"id":"ios","title":"iOS now?","kind":"Confirm","yes":"Yes","no":"Later"},
            {"id":"caller","title":"Who calls?","kind":"SingleChoice","isOptional":true,
             "choices":[{"id":"agent","title":"Agent"},{"id":"user","title":"User"}]}
        ]}""",
    ).jsonObject

    @Test
    fun `questions of the batch are asked to the chat and the answers return to the caller`() = runTest {
        val fixture = Fixture(this)
        val call = launch { fixture.execute(arguments) }
        repeat(ASK_SUBSCRIPTION_ROUNDS) { runCurrent() }

        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()
        assertTrue(asked.all { it.questionnaire.id.value.startsWith("live/s1/") })
        assertEquals(listOf("ios", "caller"), asked.map { it.questionnaire.id.value.substringAfterLast("/") })
        assertTrue(asked.all { it.questionnaire.source == "s1" })

        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(asked[0].questionnaire, Answer.Confirmed(false)))
        fixture.queue.outputs.emit(
            QuestionnaireOutput.Answered(asked[1].questionnaire, Answer.Selected(listOf("agent"))),
        )
        runCurrent()
        call.join()

        val answers = fixture.answers()
        assertEquals(false, answers.getValue("ios").jsonObject.getValue("isConfirmed").jsonPrimitive.boolean)
        assertEquals(
            listOf("agent"),
            answers.getValue("caller").jsonObject.getValue("selected").jsonArray.map { it.jsonPrimitive.content },
        )
        // Every answer is withdrawn once collected.
        assertEquals(
            asked.map { QuestionnaireIntent.Public.Withdraw(it.questionnaire.id) },
            fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Withdraw>(),
        )
    }

    @Test
    fun `a skipped optional question carries no answer and its card is withdrawn`() = runTest {
        val fixture = Fixture(this)
        val optional = Json.parseToJsonElement(
            """{"key":"plan","questions":[
                {"id":"notes","title":"Notes","kind":"FreeText","isOptional":true}
            ]}""",
        ).jsonObject
        val call = launch { fixture.execute(optional) }
        repeat(ASK_SUBSCRIPTION_ROUNDS) { runCurrent() }
        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()

        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(asked[0].questionnaire, Answer.Skipped))
        runCurrent()
        call.join()

        val notes = fixture.answers().getValue("notes").jsonObject
        assertTrue(notes.getValue("isSkipped").jsonPrimitive.boolean)
        assertFalse("text" in notes)
    }

    @Test
    fun `a cancelled call withdraws the questions that are still unanswered`() = runTest {
        val fixture = Fixture(this)
        val call = launch { fixture.execute(arguments) }
        repeat(ASK_SUBSCRIPTION_ROUNDS) { runCurrent() }
        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()

        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(asked[0].questionnaire, Answer.Confirmed(true)))
        runCurrent()
        call.cancel()
        runCurrent()

        val withdrawn = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Withdraw>()
        assertEquals(
            asked.map { it.questionnaire.id.value },
            withdrawn.map { it.id.value }.distinct(),
        )
        assertEquals(2, withdrawn.size)
    }

    @Test
    fun `answers received while the batch is still being published are not lost`() = runTest {
        val fixture = Fixture(this)
        fixture.queue.onSend = { intent ->
            if (intent is QuestionnaireIntent.Public.Ask) {
                val answer = if (intent.questionnaire.title == "iOS now?") {
                    Answer.Confirmed(true)
                } else {
                    Answer.Selected(listOf("user"))
                }
                fixture.queue.outputs.emit(QuestionnaireOutput.Answered(intent.questionnaire, answer))
            }
        }

        val call = launch { fixture.execute(arguments) }
        runCurrent()

        assertTrue(call.isCompleted)
        assertEquals(setOf("ios", "caller"), fixture.answers().keys)
        assertEquals(0, fixture.queue.outputs.subscriptionCount.value)
    }

    @Test
    fun `cancellation during answer withdrawal retries cleanup without cancellation`() = runTest {
        val fixture = Fixture(this)
        val withdrawing = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        var isFirstWithdrawal = true
        fixture.queue.onSend = { intent ->
            if (intent is QuestionnaireIntent.Public.Withdraw && isFirstWithdrawal) {
                isFirstWithdrawal = false
                withdrawing.complete(Unit)
                blocked.await()
            }
        }
        val call = launch { fixture.execute(arguments) }
        runCurrent()
        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()
        fixture.queue.outputs.emit(QuestionnaireOutput.Answered(asked.first().questionnaire, Answer.Confirmed(true)))
        withdrawing.await()

        call.cancelAndJoin()

        assertEquals(
            asked.map { it.questionnaire.id }.toSet(),
            fixture.queue.applied.filterIsInstance<QuestionnaireIntent.Public.Withdraw>().map { it.id }.toSet(),
        )
        assertEquals(0, fixture.queue.outputs.subscriptionCount.value)
    }

    @Test
    fun `parallel batches with repeated or ambiguous keys own different cards`() = runTest {
        listOf("plan" to "q", "plan/a" to "q").forEach { (key, id) ->
            val fixture = Fixture(this)
            val firstArgs = Json.parseToJsonElement(
                """{"key":"$key","questions":[{"id":"$id","title":"First","kind":"FreeText"}]}""",
            ).jsonObject
            val secondId = if (key == "plan") "q" else "a/q"
            val secondArgs = Json.parseToJsonElement(
                """{"key":"plan","questions":[
                    {"id":"$secondId","title":"Second","kind":"Confirm","yes":"Yes","no":"No"}]}""",
            ).jsonObject
            val first = launch { fixture.execute(firstArgs) }
            runCurrent()
            val second = launch { fixture.execute(secondArgs) }
            runCurrent()
            val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()
            assertEquals(2, asked.map { it.questionnaire.id }.distinct().size)

            first.cancelAndJoin()
            val withdrawn = fixture.queue.applied.filterIsInstance<QuestionnaireIntent.Public.Withdraw>()
            assertEquals(listOf(asked[0].questionnaire.id), withdrawn.map { it.id })
            assertFalse(second.isCompleted)

            fixture.queue.outputs.emit(QuestionnaireOutput.Answered(asked[1].questionnaire, Answer.Confirmed(false)))
            runCurrent()
            assertTrue(second.isCompleted)
            assertEquals(setOf(secondId), fixture.answers().keys)
        }
    }

    @Test
    fun `a refused partial batch withdraws published questions and stops its collector`() = runTest {
        val fixture = Fixture(this)
        fixture.queue.result = { intent ->
            if (intent is QuestionnaireIntent.Public.Ask && intent.questionnaire.title == "Who calls?") {
                SendResult.NotRunning
            } else {
                SendResult.Accepted
            }
        }

        fixture.execute(arguments)

        assertTrue(fixture.errorText().contains("refused"))
        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()
        val withdrawn = fixture.queue.applied.filterIsInstance<QuestionnaireIntent.Public.Withdraw>()
        assertTrue(asked.first().questionnaire.id in withdrawn.map { it.id })
        assertEquals(0, fixture.queue.outputs.subscriptionCount.value)
    }

    @Test
    fun `a session the studio does not host refuses to ask`() = runTest {
        val fixture = Fixture(this)
        fixture.chats.id = null
        fixture.execute(arguments)
        assertTrue(fixture.queue.sent.isEmpty())
        assertTrue(fixture.errorText().contains("studio"))
    }

    @Test
    fun `malformed specifications are refused without asking`() = runTest {
        val fixture = Fixture(this)
        fixture.execute(Json.parseToJsonElement("""{"key":"plan","questions":[]}""").jsonObject)
        assertTrue(fixture.queue.sent.isEmpty())
        assertTrue(fixture.errorText().contains("Invalid"))
    }

    @Test
    fun `a disabled questionnaire offers no tool and refuses calls`() = runTest {
        val fixture = Fixture(this, enabled = false)
        assertTrue(fixture.tools.specifications(null).isEmpty())
        assertEquals("", fixture.tools.instructions(null))
        fixture.execute(arguments)
        assertTrue(fixture.queue.sent.isEmpty())
        assertTrue(fixture.errorText().contains("disabled"))
    }

    private inner class Fixture(scope: TestScope, enabled: Boolean = true) {
        val queue = AskUserQueue()
        val chats = AskUserChats()
        val tools = AskUserAgentTools(askUserRegistry(queue), lazyOf(chats), AskUserToggles(enabled))
        private val results = mutableListOf<AgentToolResult>()

        suspend fun execute(arguments: JsonObject) {
            results += tools.execute(context, "questionnaire_ask", arguments)
        }

        fun answers(): JsonObject {
            val result = results.single()
            assertFalse(result.isError, result.text)
            return Json.parseToJsonElement(result.text).jsonObject.getValue("answers").jsonObject
        }

        fun errorText(): String {
            val result = results.single()
            assertTrue(result.isError, result.text)
            return result.text
        }
    }
}

private class AskUserChats(var id: String? = "s1") : StudioChatResolver {
    override suspend fun sessionIdOf(ref: SessionRef): String? = id
}

private class AskUserToggles(private val enabled: Boolean) : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // The test serves one flag.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(enabled as T)

    @Suppress("UNCHECKED_CAST") // The test serves one flag.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = enabled as T
}

private class AskUserQueue : MachineRef<QuestionnaireState, QuestionnaireIntent.Public, QuestionnaireOutput> {
    override val name = "questionnaire"
    override val state = MutableStateFlow<QuestionnaireState>(QuestionnaireState.Idle)
    override val outputs = MutableSharedFlow<QuestionnaireOutput>()
    val sent = mutableListOf<QuestionnaireIntent>()
    val applied = mutableListOf<QuestionnaireIntent>()
    var onSend: suspend (QuestionnaireIntent.Public) -> Unit = {}
    var result: (QuestionnaireIntent.Public) -> SendResult = { SendResult.Accepted }

    override suspend fun send(intent: QuestionnaireIntent.Public): SendResult {
        sent += intent
        onSend(intent)
        return result(intent).also { if (it == SendResult.Accepted) applied += intent }
    }
}

private fun askUserRegistry(queue: AskUserQueue) = object : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // The test registry serves only the questionnaire key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O> = queue as MachineRef<S, P, O>

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key).send(intent)
}

private const val ASK_SUBSCRIPTION_ROUNDS = 5
