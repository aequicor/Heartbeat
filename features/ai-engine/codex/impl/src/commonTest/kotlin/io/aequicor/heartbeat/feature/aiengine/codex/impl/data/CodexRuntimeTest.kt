@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CodexRuntimeTest {
    @Test
    fun `tree subscribers never steal streaming deltas or engine requests`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val trees = (fixture.runtime.features.resolve(SessionTrees) as FeatureAccess.Available).feature
        repeat(2) {
            backgroundScope.launch { trees.observe(session.ref, SessionTreeAccess(fixture.target)).collect() }
        }
        runCurrent()
        session.feature(SendsPrompts).send(Prompt)
        repeat(20) {
            fixture.event("item/agentMessage/delta", "itemId" to "reply".json(), "delta" to "x".json())
            runCurrent()
        }
        fixture.event("unsupported/request", id = JsonPrimitive(901))
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        val reply = session.feature(SessionHistory).page().items.filterIsInstance<SessionItem.Message>()
            .last().parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        assertEquals("x".repeat(20), reply)
        assertTrue(fixture.wire.written.any { it["id"] == JsonPrimitive(901) && "error" in it })
        assertIs<ActiveSessionState.Ready>(session.state.value)
    }

    @Test
    fun `image-only prompt is accepted using a single native localImage part`() = runTest {
        val fixture = Fixture(this)
        fixture.modelList = listOf(
            json(
                "model" to "model".json(),
                "inputModalities" to JsonArray(listOf("image".json())),
            ),
        )
        fixture.resources = ResourceResolver {
            ResolvedResource("image.png", "image/png", byteArrayOf(1), "/private/app/image.png")
        }
        val session = fixture.open()
        val request = PromptRequest(
            RequestId("image-only"),
            listOf(
                ContentPart.Image(ResourceRef("attachment:image", "image/png")),
            ),
        )
        session.feature(SendsPrompts).send(request)
        val native = fixture.wire.written.single {
            it.text(
                "method",
            ) == "turn/start"
        }.obj("params")["input"] as JsonArray
        assertEquals(1, native.size)
        assertEquals("localImage", (native.single() as JsonObject).text("type"))
        assertIs<ActiveSessionState.Running>(session.state.value)
    }

    @Test
    fun `new threads register search tools and answer dynamic calls`() = runTest {
        val fixture = Fixture(
            this,
            object : SearchEngine {
                override suspend fun search(query: String, count: Int, native: EngineFeatures?) =
                    listOf(SearchResult("https://example.com", "Example", "Snippet"))
                override suspend fun fetch(url: String, native: EngineFeatures?) =
                    ResourceContent(url, "Example", "Page")
            },
        )
        val session = fixture.open()
        val params = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        val tools = checkNotNull(params["dynamicTools"]) as JsonArray
        assertEquals(setOf("web_search", "web_fetch"), tools.map { (it as JsonObject).text("name") }.toSet())
        assertEquals("live", params.obj("config").text("web_search"))
        session.feature(SendsPrompts).send(Prompt)
        fixture.event(
            "item/tool/call",
            "turnId" to "native-turn".json(),
            "tool" to "web_search".json(),
            "arguments" to json("query" to "topic".json()),
            id = JsonPrimitive(88),
        )
        runCurrent()
        val response = fixture.wire.written.last { it["id"] == JsonPrimitive(88) }.obj("result")
        assertEquals("true", response["success"]?.toString())
        assertTrue(response.toString().contains("https://example.com"))
    }

    @Test
    fun `disabled search toggle starts threads without dynamic tools`() = runTest {
        val fixture = Fixture(this, searchTools = false)
        fixture.open()
        val params = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        assertFalse("dynamicTools" in params)
        assertFalse("web_search" in params.obj("config"))
    }

    @Test
    fun `slow tool call does not block events and is cancelled with its turn while late calls are refused`() = runTest {
        val fixture = Fixture(
            this,
            object : SearchEngine {
                override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> =
                    awaitCancellation()
                override suspend fun fetch(url: String, native: EngineFeatures?) = awaitCancellation()
            },
        )
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        suspend fun toolCall(id: Int) = fixture.event(
            "item/tool/call",
            "turnId" to "native-turn".json(),
            "tool" to "web_search".json(),
            "arguments" to json("query" to "topic".json()),
            id = JsonPrimitive(id),
        )
        toolCall(89)
        runCurrent()
        session.feature(CancelsTurns).cancel(turn)
        runCurrent()
        val response = fixture.wire.written.last { it["id"] == JsonPrimitive(89) }.obj("result")
        assertEquals("false", response["success"]?.toString())
        assertTrue(response.toString().contains("Cancelled"))
        toolCall(90)
        runCurrent()
        assertTrue(fixture.wire.written.last { it["id"] == JsonPrimitive(90) }.toString().contains("TurnUnavailable"))
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        assertIs<ActiveSessionState.Ready>(session.state.value)
    }

    @Test
    fun `toggle turned off after runtime start drops dynamic tools and refuses tool calls`() = runTest {
        var searches = 0
        val fixture = Fixture(
            this,
            object : SearchEngine {
                override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
                    searches++
                    return listOf(SearchResult("https://example.com", "Example", "Snippet"))
                }
                override suspend fun fetch(url: String, native: EngineFeatures?) =
                    ResourceContent(url, "Example", "Page")
            },
        )
        fixture.isSearchEnabled = false
        val session = fixture.open()
        val params = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        assertFalse("dynamicTools" in params)
        session.feature(SendsPrompts).send(Prompt)
        fixture.event(
            "item/tool/call",
            "turnId" to "native-turn".json(),
            "tool" to "web_search".json(),
            "arguments" to json("query" to "topic".json()),
            id = JsonPrimitive(91),
        )
        runCurrent()
        val response = fixture.wire.written.last { it["id"] == JsonPrimitive(91) }.obj("result")
        assertEquals("false", response["success"]?.toString())
        assertTrue(response.toString().contains("Disabled"))
        assertEquals(0, searches)
    }

    @Test
    fun `send waits for acceptance and close only releases its lease`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        assertIs<ActiveSessionState.Running>(session.state.value)
        session.close()
        assertEquals(ActiveSessionState.Closed, session.state.value)
        assertFalse(fixture.wire.isClosed)
        val second = fixture.runtime.attach(session.ref, ResumeSessionRequest(fixture.target))
        assertEquals(turn, assertIs<ActiveSessionState.Running>(second.state.value).turn.id)
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(second.state.value).lastTurn?.outcome)
    }

    @Test
    fun `concurrent prompts are rejected and cancellation waits for native confirmation`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        val failure = assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), failure.failure)
        session.feature(CancelsTurns).cancel(turn)
        runCurrent()
        assertIs<ActiveSessionState.Interrupting>(session.state.value)
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "interrupted".json()))
        runCurrent()
        assertEquals(TurnOutcome.Cancelled, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }

    @Test
    fun `native mutation approval never escalates the read only sandbox`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.event(
            "item/commandExecution/requestApproval",
            "turnId" to "native-turn".json(),
            "command" to "test".json(),
            id = JsonPrimitive(42),
        )
        runCurrent()
        assertIs<ActiveSessionState.Running>(session.state.value)
        val answer = fixture.wire.written.single { it["id"] == JsonPrimitive(42) }.obj("result")
        assertEquals("decline", answer.text("decision"))
    }

    @Test
    fun `account switch prevents sending and never falls back to another login`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        fixture.account = json("type" to "chatgpt".json(), "email" to "changed@example.invalid".json())
        val failure = assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertEquals("auth.SourceChanged", failure.failure.code)
        assertFalse(fixture.wire.written.any { it.text("method") == "turn/start" })
        assertTrue(fixture.runtime.isClosed)
    }

    @Test
    fun `plan change keeps the runtime bound to the same login`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        fixture.account = json(
            "type" to "chatgpt".json(),
            "email" to "local@example.invalid".json(),
            "planType" to "pro".json(),
        )
        session.feature(SendsPrompts).send(Prompt)
        assertFalse(fixture.runtime.isClosed)
    }

    @Test
    fun `thread start uses app-server v2 approval and sandbox spellings`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        val params = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        assertEquals("never", params.text("approvalPolicy"))
        assertEquals("read-only", params.text("sandbox"))
    }

    @Test
    fun `rejected turn start returns the session to ready for the next prompt`() = runTest {
        val fixture = Fixture(this)
        val accept = fixture.onTurn
        fixture.onTurn = { fixture.wire.error(it) }
        val session = fixture.open()
        val failure = assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid), failure.failure)
        runCurrent()
        assertIs<TurnOutcome.Failed>(assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        fixture.onTurn = accept
        session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("retry")))
        assertIs<ActiveSessionState.Running>(session.state.value)
    }

    @Test
    fun `unknown submission outcome is reconciled from the native thread`() = runTest {
        val fixture = Fixture(this)
        fixture.onTurn = { }
        val session = fixture.open()
        val failure = assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertEquals(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, Prompt.id), failure.failure)
        runCurrent()
        assertTrue(fixture.wire.written.any { it.text("method") == "thread/read" })
        assertEquals(TurnOutcome.Unknown, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }

    @Test
    fun `ambiguous start never adopts an unmapped running turn`() = runTest {
        val fixture = Fixture(this)
        fixture.onTurn = { }
        fixture.threadTurns = listOf(nativeTurn("foreign", "inProgress"))
        val session = fixture.open()
        assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
        fixture.event(
            "item/commandExecution/requestApproval",
            "turnId" to "foreign".json(),
            "command" to "rm".json(),
            id = JsonPrimitive(11),
        )
        runCurrent()
        val reply = fixture.wire.written.single { it["id"] == JsonPrimitive(11) }
        assertEquals("decline", reply.obj("result").text("decision"))
        fixture.threadTurns = listOf(nativeTurn("foreign", "completed"))
        fixture.event("turn/completed", "turn" to nativeTurn("foreign", "completed"))
        runCurrent()
        assertEquals(TurnOutcome.Unknown, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }

    @Test
    fun `interrupt timeout restores a turn that is still running`() = runTest {
        val fixture = Fixture(this)
        val base = fixture.wire.handler
        fixture.wire.handler = { if (it.text("method") != "turn/interrupt") base(it) }
        fixture.threadTurns = listOf(nativeTurn("native-turn", "inProgress"))
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        session.feature(CancelsTurns).cancel(turn)
        advanceTimeBy(INTERRUPT_TIMEOUT)
        runCurrent()
        assertEquals(turn, assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
    }

    @Test
    fun `foreign running turn keeps session unavailable until its completion`() = runTest {
        val fixture = Fixture(this)
        val base = fixture.wire.handler
        fixture.wire.handler = { if (it.text("method") != "turn/interrupt") base(it) }
        fixture.threadTurns = listOf(nativeTurn("other", "inProgress"))
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        session.feature(CancelsTurns).cancel(turn)
        advanceTimeBy(INTERRUPT_TIMEOUT)
        runCurrent()
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
        fixture.threadTurns = listOf(nativeTurn("other", "completed"))
        fixture.event("turn/completed", "turn" to nativeTurn("other", "completed"))
        runCurrent()
        assertEquals(TurnOutcome.Unknown, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }

    private fun nativeTurn(id: String, status: String) = json("id" to id.json(), "status" to status.json())

    @Test
    fun `rejected interrupt keeps waiting for native completion`() = runTest {
        val fixture = Fixture(this)
        val base = fixture.wire.handler
        fixture.wire.handler = { if (it.text("method") == "turn/interrupt") fixture.wire.error(it) else base(it) }
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        session.feature(CancelsTurns).cancel(turn)
        runCurrent()
        assertIs<ActiveSessionState.Interrupting>(session.state.value)
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        // A completion in a healthy session never probes the native thread.
        assertFalse(fixture.wire.written.any { it.text("method") == "thread/read" })
    }

    @Test
    fun `approval the machine cannot surface is declined`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        session.feature(CancelsTurns).cancel(turn)
        runCurrent()
        fixture.event(
            "item/commandExecution/requestApproval",
            "turnId" to "native-turn".json(),
            id = JsonPrimitive(7),
        )
        runCurrent()
        assertIs<ActiveSessionState.Interrupting>(session.state.value)
        val reply = fixture.wire.written.single { it["id"] == JsonPrimitive(7) }
        assertEquals("decline", reply.obj("result").text("decision"))
    }

    @Test
    fun `server request for an unopened thread is rejected instead of buffered`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        fixture.wire.event(
            "item/commandExecution/requestApproval",
            json("threadId" to "other".json()),
            JsonPrimitive(9),
        )
        runCurrent()
        assertTrue(fixture.wire.written.any { it["id"] == JsonPrimitive(9) && it["error"] != null })
    }

    @Test
    fun `completion before response cannot accept the next submission`() = runTest {
        val fixture = Fixture(this)
        val pending = mutableListOf<JsonObject>()
        fixture.onTurn = { pending += it }
        val session = fixture.open()
        val first = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        fixture.event("turn/started", "turn" to json("id" to "first".json()))
        fixture.event("turn/completed", "turn" to json("id" to "first".json(), "status" to "completed".json()))
        runCurrent()
        val firstId = first.await()
        val second = async { session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("second"))) }
        runCurrent()
        fixture.wire.reply(pending[0], json("turn" to json("id" to "first".json())))
        runCurrent()
        assertFalse(second.isCompleted)
        fixture.wire.reply(pending[1], json("turn" to json("id" to "second".json())))
        assertNotEquals(firstId, second.await())
    }

    @Test
    fun `transport loss after send reports unknown acceptance`() = runTest {
        val fixture = Fixture(this)
        fixture.onTurn = { fixture.wire.incoming.close() }
        val session = fixture.open()
        val failure = assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
        assertTrue(failure.failure is EngineFailure.Request || failure.failure is EngineFailure.Engine)
    }

    @Test
    fun `late error from completed turn cannot fail the next turn`() = runTest {
        val fixture = Fixture(this)
        val pending = mutableListOf<JsonObject>()
        fixture.onTurn = { pending += it }
        val session = fixture.open()
        val first = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        fixture.event("turn/started", "turn" to json("id" to "first".json()))
        fixture.event("turn/completed", "turn" to json("id" to "first".json(), "status" to "completed".json()))
        runCurrent()
        first.await()
        val second = async { session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("second"))) }
        runCurrent()
        fixture.wire.incoming.send(
            json("id" to checkNotNull(pending[0]["id"]), "error" to json("code" to JsonPrimitive(-1))),
        )
        runCurrent()
        assertIs<ActiveSessionState.Submitting>(session.state.value)
        assertFalse(second.isCompleted)
        fixture.wire.reply(pending[1], json("turn" to json("id" to "second".json())))
        second.await()
        assertIs<ActiveSessionState.Running>(session.state.value)
    }

    @Test
    fun `closed lease stops history observation and denies retained capabilities`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val history = session.feature(SessionHistory)
        val events = mutableListOf<SessionEvent>()
        val checkpoint = history.page().checkpoint
        val watching = launch { history.watch(checkpoint).collect { events += it } }
        runCurrent()
        session.close()
        runCurrent()
        assertTrue(watching.isCompleted)
        assertIs<FeatureAccess.Unavailable>(session.features.resolve(SendsPrompts))
        assertFailsWith<EngineException> { history.page() }
    }

    @Test
    fun `failed transport retires runtime and keeps ambiguous request correlation`() = runTest {
        val fixture = Fixture(this)
        fixture.onTurn = { fixture.wire.incoming.close() }
        val session = fixture.open()
        val failure = assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertEquals(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, Prompt.id), failure.failure)
        runCurrent()
        assertTrue(fixture.runtime.isClosed)
    }

    @Test
    fun `invalid history checkpoint emits invalidation and ends lease subscription`() = runTest {
        val session = Fixture(this).open()
        val events = session.feature(SessionHistory).watch(HistoryCheckpoint("foreign:0")).toList()
        assertIs<SessionEvent.HistoryInvalidated>(events.single())
    }

    @Test
    fun `profile shutdown finishes pending submission and all observations synchronously`() = runTest {
        val fixture = Fixture(this)
        fixture.onTurn = { }
        val session = fixture.open()
        val history = session.feature(SessionHistory)
        val checkpoint = history.page().checkpoint
        val watching = launch { history.watch(checkpoint).collect { } }
        val sending = async {
            assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        }
        runCurrent()
        fixture.profile.close()
        runCurrent()
        assertEquals(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, Prompt.id), sending.await().failure)
        assertTrue(watching.isCompleted)
        assertTrue(fixture.runtime.isClosed)
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
    }

    private companion object {
        const val INTERRUPT_TIMEOUT = 31_000L
    }
}
