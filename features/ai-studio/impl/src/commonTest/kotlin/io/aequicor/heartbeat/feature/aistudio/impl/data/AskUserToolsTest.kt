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
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineSpec
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        fixture.queue.emitAnswer(QuestionnaireOutput.Answered(asked[0].questionnaire, Answer.Confirmed(false)))
        fixture.queue.emitAnswer(
            QuestionnaireOutput.Answered(asked[1].questionnaire, Answer.Selected(listOf("agent"))),
        )
        runCurrent()
        call.join()

        assertEquals(listOf(context.session), fixture.chats.invalidations)
        val answers = fixture.answers()
        assertEquals(false, answers.getValue("ios").jsonObject.getValue("isConfirmed").jsonPrimitive.boolean)
        assertEquals(
            listOf("agent"),
            answers.getValue("caller").jsonObject.getValue("selected").jsonArray.map { it.jsonPrimitive.content },
        )
        // Every answer is withdrawn once collected.
        assertEquals(
            asked.map { QuestionnaireIntent.Public.Withdraw(it.questionnaire.id, it.generation) },
            fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Withdraw>(),
        )
    }

    @Test
    fun `choice and confirmation comments are delivered alongside the selected answer`() = runTest {
        val fixture = Fixture(this)
        val call = launch { fixture.execute(arguments) }
        runCurrent()
        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()
        assertTrue(asked.all { it.questionnaire.isFreeTextAllowed })
        fixture.queue.emitAnswer(
            QuestionnaireOutput.Answered(
                asked[0].questionnaire,
                Answer.Confirmed(false, "After review"),
            ),
        )
        fixture.queue.emitAnswer(
            QuestionnaireOutput.Answered(
                asked[1].questionnaire,
                Answer.Selected(listOf("user"), "From the desktop"),
            ),
        )
        runCurrent()
        call.join()
        assertEquals(
            "After review",
            fixture.answers().getValue("ios").jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertEquals(
            "From the desktop",
            fixture.answers().getValue("caller").jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `fully skipped hosted batches do not revoke authority`() = runTest {
        val fixture = Fixture(this)
        fixture.chats.revocation = CompletableDeferred()
        val call = launch { fixture.execute(arguments) }
        repeat(ASK_SUBSCRIPTION_ROUNDS) { runCurrent() }
        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()
        asked.forEach { fixture.queue.emitAnswer(QuestionnaireOutput.Answered(it.questionnaire, Answer.Skipped)) }
        runCurrent()
        // A fully skipped batch contains no new human restriction.
        assertTrue(fixture.chats.invalidations.isEmpty())
        call.join()
    }

    @Test
    fun `failed hosted revocation never returns substantive answers to the model`() = runTest {
        val fixture = Fixture(this)
        fixture.chats.revocation = CompletableDeferred()
        val call = async {
            assertFailsWith<IllegalStateException> { fixture.execute(arguments) }
        }
        repeat(ASK_SUBSCRIPTION_ROUNDS) { runCurrent() }
        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()
        fixture.queue.emitAnswer(QuestionnaireOutput.Answered(asked[0].questionnaire, Answer.Confirmed(false)))
        fixture.queue.emitAnswer(QuestionnaireOutput.Answered(asked[1].questionnaire, Answer.Skipped))
        runCurrent()
        assertEquals(listOf(context.session), fixture.chats.invalidations)
        assertFalse(call.isCompleted)
        fixture.chats.revocation.completeExceptionally(IllegalStateException("Revocation failed"))
        call.await()
    }

    @Test
    fun `a mixed batch accepts Confirm without labels and returns all answers`() = runTest {
        val fixture = Fixture(this)
        val mixed = Json.parseToJsonElement(
            """{"key":"scope","questions":[
                {"id":"depth","title":"Depth","kind":"SingleChoice",
                 "choices":[{"id":"guide","title":"Guide"}]},
                {"id":"release","title":"Release only by user?","kind":"Confirm"},
                {"id":"feedback","title":"Feedback","kind":"MultiChoice",
                 "choices":[{"id":"chat","title":"Chat"},{"id":"issues","title":"Issues"}]},
                {"id":"name","title":"Name","kind":"FreeText"}
            ]}""",
        ).jsonObject
        val call = launch { fixture.execute(mixed) }
        runCurrent()
        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()
        assertEquals(4, asked.size)
        assertEquals(Question.Confirm("Да", "Нет"), asked[1].questionnaire.question)
        val replies = listOf(
            Answer.Selected(listOf("guide")),
            Answer.Confirmed(true),
            Answer.Selected(listOf("chat", "issues")),
            Answer.Text("product_dev"),
        )
        asked.zip(replies).forEach { (intent, answer) ->
            fixture.queue.emitAnswer(QuestionnaireOutput.Answered(intent.questionnaire, answer))
        }
        runCurrent()
        call.join()

        val answers = fixture.answers()
        assertEquals(setOf("depth", "release", "feedback", "name"), answers.keys)
        assertTrue(answers.getValue("release").jsonObject.getValue("isConfirmed").jsonPrimitive.boolean)
        assertEquals(
            listOf("chat", "issues"),
            answers.getValue("feedback").jsonObject.getValue("selected").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("product_dev", answers.getValue("name").jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `Confirm defaults each absent or blank label and preserves custom labels`() = runTest {
        val variants = listOf(
            "" to Question.Confirm("Да", "Нет"),
            "\"yes\":\"Proceed\"," to Question.Confirm("Proceed", "Нет"),
            "\"no\":\"Later\"," to Question.Confirm("Да", "Later"),
            "\"yes\":\"\",\"no\":\"   \"," to Question.Confirm("Да", "Нет"),
            "\"yes\":\"Proceed\",\"no\":\"Later\"," to Question.Confirm("Proceed", "Later"),
        )
        variants.forEach { (labels, expected) ->
            val fixture = Fixture(this)
            val args = Json.parseToJsonElement(
                """{"key":"confirm","questions":[{$labels"id":"q","title":"Continue?","kind":"Confirm"}]}""",
            ).jsonObject
            val call = launch { fixture.execute(args) }
            runCurrent()
            val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>().single()
            assertEquals(expected, asked.questionnaire.question)
            fixture.queue.emitAnswer(QuestionnaireOutput.Answered(asked.questionnaire, Answer.Confirmed(false)))
            runCurrent()
            call.join()
            assertFalse(fixture.answers().getValue("q").jsonObject.getValue("isConfirmed").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `invalid later question returns its reason without publishing a partial batch`() = runTest {
        val fixture = Fixture(this)
        fixture.execute(
            Json.parseToJsonElement(
                """{"key":"plan","questions":[
                    {"id":"confirm","title":"Continue?","kind":"Confirm"},
                    {"id":"choices","title":"Select","kind":"MultiChoice","min":2,
                     "choices":[{"id":"one","title":"One"}]}
                ]}""",
            ).jsonObject,
        )

        assertTrue(fixture.queue.sent.isEmpty())
        assertEquals(
            "Invalid questionnaire_ask specification: MultiChoice bounds do not fit its choices",
            fixture.errorText(),
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

        fixture.queue.emitAnswer(QuestionnaireOutput.Answered(asked[0].questionnaire, Answer.Skipped))
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

        fixture.queue.emitAnswer(QuestionnaireOutput.Answered(asked[0].questionnaire, Answer.Confirmed(true)))
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
                fixture.queue.emitAnswer(QuestionnaireOutput.Answered(intent.questionnaire, answer))
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
        fixture.queue.emitAnswer(QuestionnaireOutput.Answered(asked.first().questionnaire, Answer.Confirmed(true)))
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

            fixture.queue.emitAnswer(QuestionnaireOutput.Answered(asked[1].questionnaire, Answer.Confirmed(false)))
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

    @Test
    fun `optional timeout keeps partial answers and withdraws every remaining question`() = runTest {
        val fixture = Fixture(this)
        val optional = Json.parseToJsonElement(
            """{"key":"notes","questions":[
                {"id":"one","title":"One","kind":"FreeText","isOptional":true},
                {"id":"two","title":"Two","kind":"FreeText","isOptional":true}
            ]}""",
        ).jsonObject
        val call = launch { fixture.execute(optional) }
        runCurrent()
        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()
        fixture.queue.emitAnswer(QuestionnaireOutput.Answered(asked[0].questionnaire, Answer.Text("Keep this")))
        runCurrent()
        advanceTimeBy(239_999)
        assertFalse(call.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(call.isCompleted)
        assertEquals(setOf("one"), fixture.answers().keys)
        assertEquals(listOf("two"), fixture.timedOut())
        assertEquals(
            asked.map { it.questionnaire.id },
            fixture.queue.applied.filterIsInstance<QuestionnaireIntent.Public.Withdraw>().map { it.id },
        )
        assertEquals(0, fixture.queue.outputs.subscriptionCount.value)
    }

    @Test
    fun `optional choice budget includes reading time and is capped at ten minutes`() = runTest {
        for ((length, expectedMillis) in listOf(200 to 71_000L, 20_000 to 600_000L)) {
            val fixture = Fixture(this)
            val description = "x".repeat(length)
            val args = Json.parseToJsonElement(
                """{"key":"reading","questions":[{"id":"q","title":"1234567890",
                    "kind":"SingleChoice","isOptional":true,"description":"$description",
                    "choices":[{"id":"a","title":"12345678901234567890"}]}]}""",
            ).jsonObject
            val call = launch { fixture.execute(args) }
            runCurrent()
            advanceTimeBy(expectedMillis - 1)
            assertFalse(call.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(call.isCompleted)
            assertTrue(fixture.answers().isEmpty())
            assertEquals(listOf("q"), fixture.timedOut())
            assertEquals(1, fixture.queue.applied.filterIsInstance<QuestionnaireIntent.Public.Withdraw>().size)
        }
    }

    @Test
    fun `important optional and required questions and confirmations never expire`() = runTest {
        val variants = listOf(
            "\"kind\":\"FreeText\"",
            "\"kind\":\"FreeText\",\"isOptional\":true,\"isImportant\":true",
            "\"kind\":\"Confirm\",\"isOptional\":true",
        )
        for (fields in variants) {
            val fixture = Fixture(this)
            val args = Json.parseToJsonElement(
                """{"key":"important","questions":[{"id":"q","title":"Q",$fields}]}""",
            ).jsonObject
            val call = launch { fixture.execute(args) }
            runCurrent()
            advanceTimeBy(86_400_000)
            runCurrent()
            assertFalse(call.isCompleted)
            assertTrue(fixture.queue.applied.none { it is QuestionnaireIntent.Public.Withdraw })
            call.cancelAndJoin()
        }
    }

    @Test
    fun `outer timeout withdraws unanswered cards without returning an answer`() = runTest {
        val fixture = Fixture(this)
        val call = launch { withTimeoutOrNull(1000) { fixture.execute(arguments) } }
        runCurrent()
        val asked = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>()
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(call.isCompleted)
        assertEquals(
            asked.map { it.questionnaire.id },
            fixture.queue.applied.filterIsInstance<QuestionnaireIntent.Public.Withdraw>().map { it.id },
        )
        assertEquals(0, fixture.queue.outputs.subscriptionCount.value)
        assertTrue(fixture.chats.invalidations.isEmpty())
    }

    @Test
    fun `stale live output and cleanup cannot consume an identical replacement`() = runTest {
        val fixture = Fixture(this)
        val call = launch { fixture.execute(arguments) }
        runCurrent()
        val original = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>().first()
        val replacement = QuestionnaireIntent.Public.Ask(original.questionnaire)
        fixture.queue.send(replacement)
        fixture.queue.outputs.emit(
            QuestionnaireOutput.Answered(
                original.questionnaire,
                Answer.Confirmed(true),
                generation = original.generation,
            ),
        )
        runCurrent()
        assertFalse(call.isCompleted)
        assertTrue(fixture.chats.invalidations.isEmpty())
        call.cancelAndJoin()
        val remaining = fixture.queue.state.value as QuestionnaireState.Asking
        assertEquals(listOf(original.questionnaire), remaining.pending)
        assertEquals(replacement.generation, remaining.generations[original.questionnaire.id])
    }

    @Test
    fun `replacement during live withdrawal is neither answered nor cancelled`() = runTest {
        val fixture = Fixture(this)
        val call = launch { fixture.execute(arguments) }
        runCurrent()
        val original = fixture.queue.sent.filterIsInstance<QuestionnaireIntent.Public.Ask>().first()
        val replacement = QuestionnaireIntent.Public.Ask(original.questionnaire)
        fixture.queue.onSend = { intent ->
            if (intent is QuestionnaireIntent.Public.Withdraw) {
                fixture.queue.onSend = {}
                fixture.queue.send(replacement)
            }
        }
        fixture.queue.emitAnswer(QuestionnaireOutput.Answered(original.questionnaire, Answer.Confirmed(true)))
        runCurrent()
        assertFalse(call.isCompleted)
        assertTrue(fixture.chats.invalidations.isEmpty())
        call.cancelAndJoin()
        val remaining = fixture.queue.state.value as QuestionnaireState.Asking
        assertEquals(listOf(original.questionnaire), remaining.pending)
        assertEquals(replacement.generation, remaining.generations[original.questionnaire.id])
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

        fun timedOut(): List<String> = Json.parseToJsonElement(results.single().text).jsonObject
            .getValue("timedOut").jsonArray.map { it.jsonPrimitive.content }

        fun errorText(): String {
            val result = results.single()
            assertTrue(result.isError, result.text)
            return result.text
        }
    }
}

private class AskUserChats(var id: String? = "s1") : StudioChatResolver {
    val invalidations = mutableListOf<SessionRef>()
    var revocation = CompletableDeferred(Unit)
    override suspend fun sessionIdOf(ref: SessionRef): String? = id
    override suspend fun invalidateUserInstructions(ref: SessionRef) {
        invalidations += ref
        revocation.await()
    }
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

    suspend fun emitAnswer(output: QuestionnaireOutput.Answered) {
        val queue = state.value as QuestionnaireState.Asking
        outputs.emit(output.copy(generation = queue.generations[output.questionnaire.id]))
    }

    override suspend fun send(intent: QuestionnaireIntent.Public): SendResult {
        sent += intent
        val result = result(intent)
        if (result != SendResult.Accepted) return result
        if (intent !is QuestionnaireIntent.Public.Ask) onSend(intent)
        val transition = QuestionnaireMachineSpec.resolve(state.value, intent)
        return if (transition == null) {
            SendResult.Ignored
        } else {
            state.value = transition.to
            applied += intent
            if (intent is QuestionnaireIntent.Public.Ask) onSend(intent)
            SendResult.Accepted
        }
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
