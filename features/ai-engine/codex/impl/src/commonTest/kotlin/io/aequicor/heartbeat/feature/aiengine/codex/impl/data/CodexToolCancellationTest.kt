package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun `native completion retains hosted cleanup until the explicit stop barrier drains it`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val search = object : SearchEngine {
            override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
            override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent = error("Unused")
        }
        val fixture = Fixture(this, search)
        val session = fixture.directSession()
        val turn = session.send(Prompt)
        session.event(toolCall())
        runCurrent()
        session.event(
            json(
                "method" to "turn/completed".json(),
                "params" to json("turn" to json("id" to "native-turn".json(), "status" to "completed".json())),
            ),
        )
        val stopped = async { session.hostedJobs.drain(turn) }
        runCurrent()
        assertFalse(stopped.isCompleted)
        cleanup.complete(Unit)
        assertTrue(stopped.await())
        fixture.runtime.close()
    }

    @Test
    fun `tool admission suspended at toggle lookup cannot reopen a completed turn`() = runTest {
        val search = RecordingSearch()
        val fixture = Fixture(this, search)
        val session = fixture.directSession()
        session.send(Prompt)
        val lookup = CompletableDeferred<Unit>()
        fixture.beforeSearchLookup = { lookup.await() }
        val event = async { session.event(toolCall()) }
        runCurrent()
        session.event(
            json(
                "method" to "turn/completed".json(),
                "params" to json("turn" to json("id" to "native-turn".json(), "status" to "completed".json())),
            ),
        )
        lookup.complete(Unit)
        event.await()
        runCurrent()
        assertEquals(0, search.calls)
        assertEquals(toolFailureResult("TurnUnavailable"), fixture.toolResponse())
        fixture.runtime.close()
    }

    @Test
    fun `drain cleans permission admission cancelled while waiting for the machine`() = runTest {
        val tools = HostedFixture()
        val fixture = Fixture(this, tools = tools)
        val session = fixture.directSession()
        session.lease()
        val turn = session.send(Prompt)
        var needed: ActiveSessionIntent.Internal.PermissionNeeded? = null
        var resolved: ActiveSessionIntent.Internal.PermissionResolved? = null
        fixture.launcher.beforeSend = { intent ->
            when (intent) {
                is ActiveSessionIntent.Internal.PermissionNeeded -> {
                    needed = intent
                    awaitCancellation()
                }

                is ActiveSessionIntent.Internal.PermissionResolved -> resolved = intent

                else -> Unit
            }
        }
        session.event(
            json(
                "id" to TOOL_REQUEST_ID,
                "method" to "item/tool/call".json(),
                "params" to json(
                    "threadId" to "thread".json(),
                    "turnId" to "native-turn".json(),
                    "tool" to "run_command".json(),
                    "arguments" to JsonObject(emptyMap()),
                ),
            ),
        )
        runCurrent()
        val pending = assertNotNull(needed)
        assertTrue(session.hostedJobs.drain(turn))
        assertEquals(pending.request.id, assertNotNull(resolved).request)
        assertEquals(0, tools.executions)
        fixture.runtime.close()
    }

    @Test
    fun `last lease retirement waits for hosted cleanup then releases automatically`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val search = object : SearchEngine {
            override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
            override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent = error("Unused")
        }
        val fixture = Fixture(this, search)
        val lease = fixture.open()
        lease.feature(SendsPrompts).send(Prompt)
        val execution = fixture.wire.peers.last()
        fixture.event(
            "item/tool/call",
            "turnId" to "native-turn".json(),
            "tool" to "web_search".json(),
            "arguments" to json("query" to "topic".json()),
            id = TOOL_REQUEST_ID,
        )
        runCurrent()
        lease.close()
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        assertFalse(execution.isClosed)
        cleanup.complete(Unit)
        runCurrent()
        assertTrue(execution.isClosed)
        fixture.runtime.close()
    }

    /** Direct delivery keeps event handling synchronous while tool and interrupt jobs share the test scheduler. */
    private suspend fun Fixture.directSession(): CodexSession {
        runtime.gate()
        return CodexSession(
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
    }

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
