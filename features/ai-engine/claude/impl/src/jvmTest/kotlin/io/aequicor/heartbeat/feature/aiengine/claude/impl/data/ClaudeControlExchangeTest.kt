package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ClaudeControlExchangeTest {
    @Test
    fun `initialize is acknowledged before user input and only top level result closes stdin`() = runTest {
        val pipe = Pipe()
        val observed = mutableListOf<JsonObject>()
        val work = async { ClaudeControlExchange(pipe, { error("No request") }, { observed += it }).run(INIT, USER) }
        runCurrent()
        val initialize = pipe.sent.single()
        assertEquals(INIT, initialize["request"])
        pipe.initialized(initialize)
        runCurrent()
        assertEquals(USER, pipe.sent.last())
        pipe.frames.send(frame("""{"type":"result","parent_tool_use_id":"child"}"""))
        runCurrent()
        assertEquals(0, pipe.closes)
        pipe.frames.send(RESULT)
        runCurrent()
        assertEquals(1, pipe.closes)
        assertFalse(work.isCompleted)
        pipe.frames.close()
        work.await()
        assertEquals(2, observed.size)
    }

    @Test
    fun `cancel withdraws suspended control without blocking reader or sending a late response`() = runTest {
        val pipe = Pipe()
        val entered = CompletableDeferred<Unit>()
        val withdrawn = CompletableDeferred<Unit>()
        val observed = mutableListOf<JsonObject>()
        val work = async {
            ClaudeControlExchange(pipe, {
                if (it.text("subtype") == "can_use_tool") {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withdrawn.complete(Unit)
                    }
                } else {
                    error("Unknown request")
                }
            }, { observed += it }).run(INIT, USER)
        }
        runCurrent()
        pipe.initialized(pipe.sent.single())
        pipe.frames.send(control("permission", "can_use_tool"))
        entered.await()
        pipe.frames.send(frame("""{"type":"assistant"}"""))
        pipe.frames.send(frame("""{"type":"control_cancel_request","request_id":"permission"}"""))
        withdrawn.await()
        pipe.frames.send(control("unknown", "future_protocol"))
        runCurrent()
        assertEquals("assistant", observed.single().text("type"))
        val replies = pipe.sent.filter { it.text("type") == "control_response" }
        val reply = replies.single()["response"] as JsonObject
        assertEquals("unknown", reply.text("request_id"))
        assertEquals("error", reply.text("subtype"))
        pipe.frames.send(RESULT)
        pipe.frames.close()
        work.await()
    }

    @Test
    fun `top level result revokes pending handler before closing input`() = runTest {
        val pipe = Pipe()
        val withdrawn = CompletableDeferred<Unit>()
        val work = async {
            ClaudeControlExchange(pipe, {
                try {
                    awaitCancellation()
                } finally {
                    withdrawn.complete(Unit)
                }
            }, {}).run(INIT, USER)
        }
        runCurrent()
        pipe.initialized(pipe.sent.single())
        pipe.frames.send(control("permission", "can_use_tool"))
        runCurrent()
        pipe.frames.send(RESULT)
        withdrawn.await()
        assertEquals(1, pipe.closes)
        assertTrue(pipe.sent.none { it.text("type") == "control_response" })
        pipe.frames.close()
        work.await()
    }

    @Test
    fun `blocked observer cannot delay cancellation or permit allow after terminal result`() = runTest {
        for (isResult in listOf(false, true)) {
            val pipe = Pipe()
            val observeEntered = CompletableDeferred<Unit>()
            val releaseObserver = CompletableDeferred<Unit>()
            val releasePermission = CompletableDeferred<Unit>()
            val withdrawn = CompletableDeferred<Unit>()
            val work = async {
                ClaudeControlExchange(pipe, {
                    try {
                        releasePermission.await()
                        frame("""{"behavior":"allow"}""")
                    } finally {
                        withdrawn.complete(Unit)
                    }
                }, {
                    observeEntered.complete(Unit)
                    releaseObserver.await()
                }).run(INIT, USER)
            }
            runCurrent()
            pipe.initialized(pipe.sent.single())
            pipe.frames.send(control("permission", "can_use_tool"))
            runCurrent()
            pipe.frames.send(if (isResult) RESULT else frame("""{"type":"assistant"}"""))
            observeEntered.await()
            if (!isResult) {
                pipe.frames.send(frame("""{"type":"control_cancel_request","request_id":"permission"}"""))
            }
            runCurrent()
            releasePermission.complete(Unit)
            withdrawn.await()
            runCurrent()
            assertTrue(pipe.sent.none { it.text("type") == "control_response" })
            releaseObserver.complete(Unit)
            if (!isResult) pipe.frames.send(RESULT)
            pipe.frames.close()
            work.await()
        }
    }

    @Test
    fun `failed initialize never submits the prompt`() = runTest {
        supervisorScope {
            val pipe = Pipe()
            val work = async { ClaudeControlExchange(pipe, { it }, {}).run(INIT, USER) }
            runCurrent()
            val id = pipe.sent.single().text("request_id")
            pipe.frames.send(
                frame(
                    """{
                    "type":"control_response",
                    "response":{"subtype":"error","request_id":"$id","error":"private"}
                }""",
                ),
            )
            assertFailsWith<IllegalStateException> { work.await() }
            assertEquals(1, pipe.sent.size)
        }
    }

    @Test
    fun `duplicate inbound ids never replace pending permission`() = runTest {
        supervisorScope {
            val pipe = Pipe()
            val work = async {
                ClaudeControlExchange(pipe, { awaitCancellation() }, {}).run(INIT, USER)
            }
            runCurrent()
            pipe.initialized(pipe.sent.single())
            pipe.frames.send(control("same", "can_use_tool"))
            pipe.frames.send(control("same", "can_use_tool"))
            assertFailsWith<io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException> { work.await() }
            assertTrue(pipe.sent.none { it.text("type") == "control_response" })
        }
    }

    @Test
    fun `cancelling exchange revokes all permission handlers`() = runTest {
        val pipe = Pipe()
        val withdrawn = CompletableDeferred<Unit>()
        val work = async {
            ClaudeControlExchange(pipe, {
                try {
                    awaitCancellation()
                } finally {
                    withdrawn.complete(Unit)
                }
            }, {}).run(INIT, USER)
        }
        runCurrent()
        pipe.initialized(pipe.sent.single())
        pipe.frames.send(control("permission", "can_use_tool"))
        runCurrent()
        work.cancelAndJoin()
        withdrawn.await()
        assertTrue(pipe.sent.none { it.text("type") == "control_response" })
    }

    private class Pipe : ClaudeDuplex {
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        val sent = mutableListOf<JsonObject>()
        var closes = 0
        override suspend fun receive(): JsonObject? = frames.receiveCatching().getOrNull()
        override suspend fun send(frame: JsonObject) {
            sent += frame
        }
        override suspend fun closeInput() {
            closes++
        }
        suspend fun initialized(request: JsonObject) {
            frames.send(
                buildJsonObject {
                    put("type", "control_response")
                    put(
                        "response",
                        buildJsonObject {
                            put("subtype", "success")
                            put("request_id", request.text("request_id"))
                            put("response", buildJsonObject {})
                        },
                    )
                },
            )
        }
    }
}

private fun frame(text: String) = Json.parseToJsonElement(text) as JsonObject
private fun control(id: String, subtype: String) = frame(
    """{"type":"control_request","request_id":"$id","request":{"subtype":"$subtype"}}""",
)
private val INIT = frame("""{"subtype":"initialize","hooks":{}}""")
private val USER = frame("""{"type":"user","message":{"role":"user","content":"hello"}}""")
private val RESULT = frame("""{"type":"result","parent_tool_use_id":null}""")
