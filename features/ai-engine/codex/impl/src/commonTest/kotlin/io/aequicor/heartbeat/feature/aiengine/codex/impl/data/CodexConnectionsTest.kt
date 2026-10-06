package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexConnectionsTest {
    @Test
    fun `execution failure retires only its session while another turn and metadata remain available`() = runTest {
        val fixture = Fixture(this).multipleThreads()
        val first = fixture.open()
        val second = fixture.open()
        first.feature(SendsPrompts).send(Prompt)
        val otherTurn = second.feature(SendsPrompts).send(Prompt)
        val peers = fixture.wire.peers
        assertEquals(2, peers.size)
        peers.first().incoming.close()
        runCurrent()
        assertIs<ActiveSessionState.Unavailable>(first.state.value)
        assertIs<ActiveSessionState.Running>(second.state.value)
        assertFalse(fixture.runtime.isClosed)
        assertFalse(peers.last().isClosed)
        second.feature(CancelsTurns).cancel(otherTurn)
        runCurrent()
        val interrupt = fixture.wire.written.single { it.text("method") == "turn/interrupt" }
        assertSame(peers.last(), fixture.wire.origin(interrupt))
        fixture.runtime.models(fixture.target.binding)
        assertSame(fixture.wire, fixture.wire.origin(fixture.wire.written.last { it.text("method") == "model/list" }))
        fixture.runtime.close()
        assertTrue(peers.all { it.isClosed })
    }

    @Test
    fun `foreign connection and metadata events cannot complete a session turn or answer its requests`() = runTest {
        val fixture = Fixture(this).multipleThreads()
        val first = fixture.open()
        val second = fixture.open()
        second.feature(SendsPrompts).send(Prompt)
        val params = json(
            "threadId" to second.ref.nativeId.json(),
            "turn" to json("id" to "native-turn".json(), "status" to "completed".json()),
        )
        fixture.wire.incoming.send(json("method" to "turn/completed".json(), "params" to params))
        fixture.wire.peers.first().event("turn/completed", params)
        fixture.wire.peers.first().event("item/tool/call", params, JsonPrimitive(991))
        runCurrent()
        assertIs<ActiveSessionState.Running>(second.state.value)
        val refusal = fixture.wire.written.single { it["id"] == JsonPrimitive(991) }
        assertTrue("error" in refusal)
        assertSame(fixture.wire.peers.first(), fixture.wire.origin(refusal))
        fixture.wire.peers.last().event("turn/completed", params)
        runCurrent()
        assertIs<ActiveSessionState.Ready>(second.state.value)
        assertIs<ActiveSessionState.Ready>(first.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `last idle handle materializes empty thread and closes only its execution process`() = runTest {
        val fixture = Fixture(this).multipleThreads()
        val idle = fixture.open()
        val active = fixture.open()
        active.feature(SendsPrompts).send(Prompt)
        val old = fixture.wire.peers.first()
        idle.close()
        runCurrent()
        assertTrue(old.isClosed)
        assertFalse(fixture.wire.peers.last().isClosed)
        assertIs<ActiveSessionState.Running>(active.state.value)
        val name = fixture.wire.written.single { it.text("method") == "thread/name/set" }.obj("params")
        assertEquals(idle.ref.nativeId, name.text("threadId"))
        assertEquals("Heartbeat", name.text("name"))
        val resumed = fixture.runtime.attach(idle.ref, ResumeSessionRequest(fixture.target))
        assertEquals(idle.ref, resumed.ref)
        assertEquals(3, fixture.wire.peers.size)
        assertSame(
            fixture.wire.peers.last(),
            fixture.wire.origin(
                fixture.wire.written.single { it.text("method") == "thread/resume" },
            ),
        )
        fixture.runtime.close()
    }

    @Test
    fun `closing last active handle keeps work alive then closes the process on completion`() = runTest {
        val fixture = Fixture(this).multipleThreads()
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        val peer = fixture.wire.peers.single()
        session.close()
        runCurrent()
        assertFalse(peer.isClosed)
        assertFalse(fixture.wire.written.any { it.text("method") == "turn/interrupt" })
        peer.event(
            "turn/completed",
            json(
                "threadId" to session.ref.nativeId.json(),
                "turn" to json("id" to "native-turn".json(), "status" to "completed".json()),
            ),
        )
        runCurrent()
        assertTrue(peer.isClosed)
        assertFalse(fixture.wire.written.any { it.text("method") == "thread/name/set" })
        assertFalse(fixture.runtime.isClosed)
        fixture.runtime.close()
    }

    @Test
    fun `rejected pending submission closes the process after its last handle was closed`() = runTest {
        val fixture = Fixture(this).multipleThreads()
        fixture.onTurn = { }
        val session = fixture.open()
        val sending = async { assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) } }
        runCurrent()
        val peer = fixture.wire.peers.single()
        val request = fixture.wire.written.single { it.text("method") == "turn/start" }
        session.close()
        runCurrent()
        assertFalse(peer.isClosed)
        fixture.wire.error(request)
        sending.await()
        runCurrent()
        assertTrue(peer.isClosed)
        assertFalse(fixture.runtime.isClosed)
        fixture.runtime.close()
    }

    @Test
    fun `equal server request ids on two processes keep completion and cancellation replies separate`() = runTest {
        val fixture = Fixture(
            this,
            object : SearchEngine {
                override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
                    if (query == "first") awaitCancellation()
                    return listOf(SearchResult("https://example.com/second", "Second", "Result"))
                }
                override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent = error("Not used")
            },
        ).multipleThreads()
        val first = fixture.open()
        val second = fixture.open()
        first.feature(SendsPrompts).send(Prompt)
        second.feature(SendsPrompts).send(Prompt)
        val peers = fixture.wire.peers
        listOf(first, second).forEachIndexed { index, session ->
            peers[index].event(
                "item/tool/call",
                json(
                    "threadId" to session.ref.nativeId.json(),
                    "turnId" to "native-turn".json(),
                    "tool" to "web_search".json(),
                    "arguments" to json("query" to (if (index == 0) "first" else "second").json()),
                ),
                JsonPrimitive(991),
            )
        }
        runCurrent()
        val success = fixture.wire.written.single { it["id"] == JsonPrimitive(991) }
        assertSame(peers.last(), fixture.wire.origin(success))
        assertEquals(JsonPrimitive(true), success.obj("result")["success"])
        peers.first().event(
            "turn/completed",
            json(
                "threadId" to first.ref.nativeId.json(),
                "turn" to json("id" to "native-turn".json(), "status" to "completed".json()),
            ),
        )
        runCurrent()
        val replies = fixture.wire.written.filter { it["id"] == JsonPrimitive(991) }
        assertEquals(2, replies.size)
        assertSame(peers.first(), fixture.wire.origin(replies.last()))
        assertEquals(toolFailureResult("Cancelled"), replies.last().obj("result"))
        assertIs<ActiveSessionState.Running>(second.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `materializing empty thread preserves its existing name`() = runTest {
        val fixture = Fixture(this).multipleThreads()
        val session = fixture.open()
        val handler = fixture.wire.handler
        fixture.wire.handler = { message ->
            if (message.text("method") == "thread/read") {
                fixture.wire.reply(
                    message,
                    json(
                        "thread" to json(
                            "id" to session.ref.nativeId.json(),
                            "name" to "Existing title".json(),
                        ),
                    ),
                )
            } else {
                handler(message)
            }
        }
        session.close()
        runCurrent()
        val request = fixture.wire.written.single { it.text("method") == "thread/name/set" }
        assertEquals("Existing title", request.obj("params").text("name"))
        assertTrue(fixture.wire.peers.single().isClosed)
        fixture.runtime.close()
    }

    @Test
    fun `runtime closing while process opens disposes the returned wire without initializing it`() = runTest {
        val opened = CompletableDeferred<CodexWire>()
        val fixture = Fixture(
            this,
            launch = object : PreparedCodexLaunch {
                override suspend fun open(): CodexWire = opened.await()
            },
        )
        val opening = async { assertFailsWith<EngineException> { fixture.open() } }
        runCurrent()
        fixture.runtime.close()
        val late = FakeWire()
        opened.complete(late)
        opening.await()
        assertTrue(late.isClosed)
        assertTrue(late.written.isEmpty())
    }

    @Test
    fun `cancelled handshake closes the opening process and leaves metadata usable`() = runTest {
        val fixture = Fixture(this)
        val handler = fixture.wire.handler
        fixture.wire.handler = { if (it.text("method") != "initialize") handler(it) }
        val opening = async { fixture.open() }
        runCurrent()
        val peer = fixture.wire.peers.single()
        assertFalse(peer.isClosed)
        opening.cancel()
        runCurrent()
        assertTrue(peer.isClosed)
        assertFalse(fixture.runtime.isClosed)
        fixture.runtime.models(fixture.target.binding)
        fixture.runtime.close()
    }

    @Test
    fun `execution process with another home is rejected before thread creation`() = runTest {
        val fixture = Fixture(this)
        val handler = fixture.wire.handler
        fixture.wire.handler = { message ->
            if (message.text("method") == "initialize") {
                val home = if (fixture.wire.origin(message) === fixture.wire) "/expected" else "/other"
                fixture.wire.reply(message, json("codexHome" to home.json()))
            } else {
                handler(message)
            }
        }
        fixture.rpc.initialize()
        assertFailsWith<EngineException> { fixture.open() }
        assertTrue(fixture.wire.peers.single().isClosed)
        assertFalse(fixture.wire.written.any { it.text("method") == "thread/start" })
        fixture.runtime.close()
    }

    @Test
    fun `execution account mismatch retires all processes before a new thread can start`() = runTest {
        val fixture = Fixture(this).multipleThreads()
        val first = fixture.open()
        val handler = fixture.wire.handler
        fixture.wire.handler = { message ->
            if (message.text("method") == "account/read" && fixture.wire.origin(message) !== fixture.wire) {
                fixture.wire.reply(
                    message,
                    json("account" to json("type" to "chatgpt".json(), "email" to "other".json())),
                )
            } else {
                handler(message)
            }
        }
        assertFailsWith<EngineException> { fixture.open() }
        runCurrent()
        assertTrue(fixture.runtime.isClosed)
        assertIs<ActiveSessionState.Unavailable>(first.state.value)
        assertTrue(fixture.wire.peers.all { it.isClosed })
        assertEquals(1, fixture.wire.written.count { it.text("method") == "thread/start" })
    }

    private fun Fixture.multipleThreads(): Fixture = apply {
        val handler = wire.handler
        var next = 0
        wire.handler = { message ->
            val method = message.text("method")
            when (method) {
                "thread/start", "thread/resume" -> {
                    val id = if (method == "thread/start") {
                        "thread-${++next}"
                    } else {
                        message.obj(
                            "params",
                        ).text("threadId")!!
                    }
                    wire.reply(
                        message,
                        json(
                            "thread" to json("id" to id.json(), "turns" to JsonArray(emptyList())),
                            "approvalPolicy" to "never".json(),
                            "sandbox" to json("type" to "readOnly".json(), "networkAccess" to JsonPrimitive(false)),
                        ),
                    )
                }

                "thread/read" -> wire.reply(
                    message,
                    json(
                        "thread" to json(
                            "id" to message.obj("params").text("threadId")!!.json(),
                            "turns" to JsonArray(emptyList()),
                        ),
                    ),
                )

                else -> handler(message)
            }
        }
    }
}
