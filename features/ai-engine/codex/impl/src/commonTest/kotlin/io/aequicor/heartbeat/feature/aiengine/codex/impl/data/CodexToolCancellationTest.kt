package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexToolCancellationTest {
    @Test
    fun `tool cancelled before its first dispatch answers once without calling the provider`() = runTest {
        val search = RecordingSearch()
        val fixture = Fixture(this, search)
        val session = fixture.directSession()
        val turn = session.send(Prompt)

        // Queue the interrupt effect first, then accept a tool call before that effect is dispatched.
        session.cancel(turn)
        session.event(toolCall())
        assertEquals(0, search.calls)
        runCurrent()

        assertEquals(0, search.calls)
        assertEquals(toolFailureResult("Cancelled"), fixture.toolResponse())
        fixture.runtime.close()
    }

    @Test
    fun `completed tool has one successful response after its turn is cancelled`() = runTest {
        val search = RecordingSearch()
        val fixture = Fixture(this, search)
        val session = fixture.directSession()
        val turn = session.send(Prompt)
        session.event(toolCall())
        runCurrent()

        session.cancel(turn)
        runCurrent()

        assertEquals(1, search.calls)
        assertEquals(JsonPrimitive(true), fixture.toolResponse()["success"])
        fixture.runtime.close()
    }

    @Test
    fun `cancellation during an already written response does not send a second response`() = runTest {
        val fixture = Fixture(this, RecordingSearch())
        val session = fixture.directSession()
        val turn = session.send(Prompt)
        val handler = fixture.wire.handler
        fixture.wire.handler = { message ->
            if (message["id"] == TOOL_REQUEST_ID) awaitCancellation()
            handler(message)
        }
        session.event(toolCall())
        runCurrent()
        assertEquals(JsonPrimitive(true), fixture.toolResponse()["success"])

        session.cancel(turn)
        runCurrent()

        assertEquals(JsonPrimitive(true), fixture.toolResponse()["success"])
        fixture.runtime.close()
    }

    /** Direct delivery keeps event handling synchronous while tool and interrupt jobs share the test scheduler. */
    private fun Fixture.directSession(): CodexSession = CodexSession(
        ref = SessionRef(runtime.identity.engine, environment.config.historySource, "thread"),
        route = ExecutionRoute(
            runtime.identity.engine,
            target.binding,
            runtime.identity.source,
            runtime.identity.revision,
        ),
        target = target,
        runtime = runtime,
        connection = CodexConnection(rpc, test.backgroundScope, {}, { _, _ -> }),
    )

    private fun Fixture.toolResponse(): JsonObject = wire.written.single { it["id"] == TOOL_REQUEST_ID }.obj("result")

    private fun toolCall(): JsonObject = json(
        "id" to TOOL_REQUEST_ID,
        "method" to "item/tool/call".json(),
        "params" to json(
            "threadId" to "thread".json(),
            "turnId" to "native-turn".json(),
            "tool" to "web_search".json(),
            "arguments" to json("query" to "topic".json()),
        ),
    )

    private class RecordingSearch : SearchEngine {
        var calls = 0
            private set

        override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
            calls++
            return emptyList()
        }

        override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent = error("Not used")
    }

    private companion object {
        val TOOL_REQUEST_ID = JsonPrimitive(501)
    }
}
