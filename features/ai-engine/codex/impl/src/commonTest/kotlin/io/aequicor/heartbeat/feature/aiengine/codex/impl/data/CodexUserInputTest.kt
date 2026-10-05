@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexUserInputTest {
    @Test
    fun `start and resume expose default mode questions only when the questionnaire is enabled`() = runTest {
        for (enabled in listOf(true, false)) {
            val fixture = Fixture(this)
            fixture.isQuestionnaireEnabled = enabled
            val session = fixture.open()
            val start = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
            assertEquals(
                JsonPrimitive(enabled),
                start.obj("config").obj("features")["default_mode_request_user_input"],
            )
            fixture.runtime.close()
            val resumed = Fixture(this)
            resumed.isQuestionnaireEnabled = enabled
            resumed.runtime.attach(session.ref, ResumeSessionRequest(resumed.target))
            val resume = resumed.wire.written.single { it.text("method") == "thread/resume" }.obj("params")
            assertEquals(
                JsonPrimitive(enabled),
                resume.obj("config").obj("features")["default_mode_request_user_input"],
            )
            resumed.runtime.close()
        }
    }

    @Test
    fun `a disabled questionnaire refuses late native questions without an unanswerable form`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.isQuestionnaireEnabled = false
        fixture.ask(question("links"))
        runCurrent()
        assertIs<ActiveSessionState.Running>(session.state.value)
        assertEquals(JsonObject(emptyMap()), checkNotNull(fixture.response())["answers"])
        fixture.runtime.close()
    }

    @Test
    fun `native questions wait for explicit answers even with full trust and collect the whole batch`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt.copy(trust = TrustLevel.Full))
        fixture.ask(question("links"), question("details", options = null))
        runCurrent()

        val first = session.question()
        assertEquals("Which links?", first.title)
        assertEquals("Websites: External pages", first.description)
        val input = assertIs<PermissionInput.SingleChoice>(first.input)
        assertEquals(listOf("Websites", "Project files"), input.choices.map { it.title })
        assertNull(fixture.response())
        session.answer(first, PermissionAnswer.Selected(listOf(input.choices.last().id)))
        runCurrent()

        val second = session.question()
        assertIs<PermissionInput.FreeText>(second.input)
        assertNotEquals(first.id, second.id)
        assertNull(fixture.response())
        session.answer(second, PermissionAnswer.Text("Relative links"))
        runCurrent()

        val answers = checkNotNull(fixture.response()).obj("answers")
        assertEquals(JsonArray(listOf("Project files".json())), answers.obj("links")["answers"])
        assertEquals(JsonArray(listOf("Relative links".json())), answers.obj("details")["answers"])
        assertIs<ActiveSessionState.Running>(session.state.value)
        assertFailsWith<EngineException> {
            session.answer(first, PermissionAnswer.Selected(listOf("0")))
        }
        fixture.runtime.close()
    }

    @Test
    fun `other opens a free text question and returns the typed answer`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.ask(question("links", other = true))
        runCurrent()
        val first = session.question()
        val choices = assertIs<PermissionInput.SingleChoice>(first.input).choices
        session.answer(first, PermissionAnswer.Selected(listOf(choices.last().id)))
        runCurrent()
        val followUp = session.question()
        assertIs<PermissionInput.FreeText>(followUp.input)
        assertNull(fixture.response())
        session.answer(followUp, PermissionAnswer.Text("Both"))
        runCurrent()
        assertEquals(
            JsonArray(listOf("Both".json())),
            checkNotNull(fixture.response()).obj("answers").obj("links")["answers"],
        )
        fixture.runtime.close()
    }

    @Test
    fun `skip returns no answer instead of the first suggested option`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.ask(question("links"))
        runCurrent()
        session.answer(session.question(), null)
        runCurrent()
        assertEquals(
            JsonArray(emptyList()),
            checkNotNull(fixture.response()).obj("answers").obj("links")["answers"],
        )
        fixture.runtime.close()
    }

    @Test
    fun `native resolution removes the question without answering it or blocking later events`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.ask(question("links"))
        runCurrent()
        val pending = session.question()
        fixture.event("serverRequest/resolved", "requestId" to JsonPrimitive(QUESTION_RPC_ID))
        runCurrent()
        assertIs<ActiveSessionState.Running>(session.state.value)
        assertNull(fixture.response())
        assertFailsWith<EngineException> { session.answer(pending, PermissionAnswer.Selected(listOf("0"))) }
        fixture.runtime.close()
    }

    @Test
    fun `interrupt answers pending questions empty and rejects late requests for that turn`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        fixture.ask(question("links"))
        runCurrent()
        val pending = session.question()
        session.feature(CancelsTurns).cancel(turn)
        runCurrent()
        assertIs<ActiveSessionState.Interrupting>(session.state.value)
        // A rejected interrupt must not leave Codex waiting on a form that no longer exists.
        assertEquals(JsonObject(emptyMap()), checkNotNull(fixture.response())["answers"])
        assertEquals(1, fixture.responses())
        assertFailsWith<EngineException> { session.answer(pending, PermissionAnswer.Selected(listOf("0"))) }
        fixture.ask(question("late"))
        runCurrent()
        assertEquals(JsonObject(emptyMap()), checkNotNull(fixture.response())["answers"])
        fixture.runtime.close()
    }

    @Test
    fun `releasing the last lease mid batch returns the answers collected so far`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.ask(question("links"), question("details", options = null))
        runCurrent()
        session.answer(session.question(), PermissionAnswer.Selected(listOf("0")))
        runCurrent()
        session.question()
        session.close()
        runCurrent()
        val answers = checkNotNull(fixture.response()).obj("answers")
        assertEquals(JsonArray(listOf("Websites".json())), answers.obj("links")["answers"])
        assertNull(answers["details"])
        assertEquals(1, fixture.responses())
        fixture.runtime.close()
    }

    @Test
    fun `turn completion withdraws an unanswered asynchronous question`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.ask(question("links"))
        runCurrent()
        session.question()
        fixture.event(
            "turn/completed",
            "turn" to json("id" to "native-turn".json(), "status" to "completed".json()),
        )
        runCurrent()
        assertIs<ActiveSessionState.Ready>(session.state.value)
        assertEquals(JsonObject(emptyMap()), checkNotNull(fixture.response())["answers"])
        assertEquals(1, fixture.responses())
        fixture.runtime.close()
    }

    @Test
    fun `malformed and secret questions do not leave an unanswerable form`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        val secret = JsonObject(question("secret") + ("isSecret" to JsonPrimitive(true)))
        assertNull(codexQuestions(json("questions" to JsonArray(listOf(question("same"), question("same"))))))
        fixture.ask(secret)
        runCurrent()
        assertIs<ActiveSessionState.Running>(session.state.value)
        assertEquals(JsonObject(emptyMap()), checkNotNull(fixture.response())["answers"])
        fixture.runtime.close()
    }

    @Test
    fun `questions never escalate native command approval`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.event(
            "item/commandExecution/requestApproval",
            "turnId" to "native-turn".json(),
            id = JsonPrimitive(QUESTION_RPC_ID),
        )
        runCurrent()
        assertEquals("decline", checkNotNull(fixture.response()).text("decision"))
        assertFalse(session.state.value is ActiveSessionState.AwaitingUserAction)
        fixture.runtime.close()
    }
}

private const val QUESTION_RPC_ID = 900

private fun question(
    id: String,
    options: JsonArray? = JsonArray(
        listOf(
            json("label" to "Websites".json(), "description" to "External pages".json()),
            json("label" to "Project files".json(), "description" to "".json()),
        ),
    ),
    other: Boolean = false,
) = json(
    "id" to id.json(),
    "header" to "Links".json(),
    "question" to "Which links?".json(),
    "options" to (options ?: JsonNull),
    "isOther" to JsonPrimitive(other),
    "isSecret" to JsonPrimitive(false),
)

private suspend fun Fixture.ask(vararg questions: JsonObject) = event(
    "item/tool/requestUserInput",
    "turnId" to "native-turn".json(),
    "itemId" to "question-tool".json(),
    "questions" to JsonArray(questions.toList()),
    "isBlocking" to JsonPrimitive(false),
    id = JsonPrimitive(QUESTION_RPC_ID),
)

private fun Fixture.response(): JsonObject? =
    wire.written.lastOrNull { it["id"] == JsonPrimitive(QUESTION_RPC_ID) }?.obj("result")

private fun Fixture.responses(): Int = wire.written.count { it["id"] == JsonPrimitive(QUESTION_RPC_ID) }

private fun ActiveSession.question(): PermissionRequest =
    assertIs<ActiveSessionState.AwaitingUserAction>(state.value).requests.single()

private suspend fun ActiveSession.answer(request: PermissionRequest, answer: PermissionAnswer?) {
    val option = request.options.first { it.isSkip == (answer == null) }
    assertTrue(request.options.isNotEmpty())
    feature(RequestsPermissions).respond(PermissionDecision(request.turn, request.id, option.id, answer))
}
