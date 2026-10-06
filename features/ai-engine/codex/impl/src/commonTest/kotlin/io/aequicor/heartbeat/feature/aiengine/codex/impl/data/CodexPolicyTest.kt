@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodexPolicyTest {
    @Test
    fun `initial off reaches execution flags and thread config while native permissions stay read only`() = runTest {
        val tools = PolicyTools().apply { current = policy(1, "shell", "web_search") }
        val fixture = Fixture(this, tools = tools)
        fixture.open()
        val peer = fixture.wire.peers.single()
        assertEquals(CodexNativeOff(true, true), peer.off)
        val start = fixture.requests("thread/start").single().obj("params")
        assertEquals(JsonPrimitive(false), start.obj("config").obj("features")["shell_tool"])
        assertEquals("disabled", start.obj("config").text("web_search"))
        assertEquals("never", start.text("approvalPolicy"))
        assertEquals("read-only", start.text("sandbox"))
        assertTrue(fixture.requests("config/read").all { it.obj("params")["includeLayers"] == JsonPrimitive(true) })
        fixture.runtime.close()
    }

    @Test
    fun `new chat rebases equal policy to its concrete scope without opening a second process`() = runTest {
        val tools = PolicyTools().apply {
            lookup = { ResolvedToolPolicy(generation = if (it.session == null) 1 else 2) }
        }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        assertEquals(1, fixture.wire.peers.size)
        assertTrue(fixture.requests("thread/resume").isEmpty())
        assertTrue(fixture.requests("thread/name/set").isEmpty())
        fixture.runtime.close()
    }

    @Test
    fun `session specific off cold resumes the same empty thread and preserves both live handles`() = runTest {
        val tools = PolicyTools().apply { lookup = { if (it.session == null) policy(1) else policy(2, "shell") } }
        val fixture = Fixture(this, tools = tools)
        val first = fixture.open()
        val second = fixture.runtime.attach(first.ref, ResumeSessionRequest(fixture.target))
        val before = first.feature(SessionHistory).page()
        first.feature(SendsPrompts).send(Prompt)
        val peers = fixture.wire.peers
        assertEquals(2, peers.size)
        assertTrue(peers.first().isClosed)
        assertEquals(first.ref, second.ref)
        assertEquals(first.route, second.route)
        assertEquals(first.ref.nativeId, fixture.requests("thread/resume").single().obj("params").text("threadId"))
        assertEquals(first.ref.nativeId, fixture.requests("thread/name/set").single().obj("params").text("threadId"))
        assertSame(peers.last(), fixture.wire.origin(fixture.requests("turn/start").single()))
        runCurrent()
        assertIs<ActiveSessionState.Running>(second.state.value)
        assertTrue(first.feature(SessionHistory).page().items.containsAll(before.items))
        fixture.runtime.close()
    }

    @Test
    fun `generation changes apply after the active turn and removal restores captured native defaults`() = runTest {
        val tools = PolicyTools().apply { current = policy(1, "shell", "web_search") }
        val fixture = Fixture(this, tools = tools)
        fixture.isSearchEnabled = false
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        tools.current = policy(2)
        assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertEquals(1, fixture.wire.peers.size)
        fixture.complete()
        runCurrent()
        session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("second")))
        assertEquals(CodexNativeOff(), fixture.wire.peers.last().off)
        val config = fixture.requests("thread/resume").single().obj("params").obj("config")
        assertFalse("shell_tool" in config.obj("features"))
        assertFalse("web_search" in config)
        assertTrue(fixture.wire.peers.first().isClosed)
        fixture.runtime.close()
    }

    @Test
    fun `a concrete generation change still reloads when policy content has returned to its previous value`() =
        runTest {
            val tools = PolicyTools().apply { current = policy(1) }
            val fixture = Fixture(this, tools = tools)
            val session = fixture.open()
            session.feature(SendsPrompts).send(Prompt)
            fixture.complete()
            runCurrent()
            tools.current = policy(3)
            session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("after-aba")))
            assertEquals(2, fixture.wire.peers.size)
            assertEquals(1, fixture.requests("thread/resume").size)
            fixture.runtime.close()
        }

    @Test
    fun `failed strict lookup refuses before opening or sending and never falls back to declaration defaults`() =
        runTest {
            val tools = PolicyTools().apply { current = null }
            val fixture = Fixture(this, tools = tools)
            assertFailsWith<EngineException> { fixture.open() }
            assertTrue(fixture.wire.peers.isEmpty())
            tools.current = policy(1, "shell")
            val session = fixture.open()
            val before = session.feature(SessionHistory).page()
            tools.current = null
            assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
            assertTrue(fixture.requests("turn/start").isEmpty())
            assertEquals(before, session.feature(SessionHistory).page())
            assertFalse(fixture.wire.peers.single().isClosed)
            tools.current = policy(2, "shell")
            session.feature(SendsPrompts).send(Prompt)
            assertIs<ActiveSessionState.Running>(session.state.value)
            fixture.runtime.close()
        }

    @Test
    fun `failed cold resume retains ready handles and retries without trying to materialize a closed process`() =
        runTest {
            val tools = PolicyTools().apply { current = policy(1) }
            val fixture = Fixture(this, tools = tools)
            val session = fixture.open()
            val handler = fixture.wire.handler
            fixture.wire.handler = { if (it.text("method") == "thread/resume") fixture.wire.error(it) else handler(it) }
            tools.current = policy(2, "shell")
            assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
            assertIs<ActiveSessionState.Ready>(session.state.value)
            assertTrue(fixture.wire.peers.all { it.isClosed })
            assertTrue(fixture.requests("turn/start").isEmpty())
            fixture.wire.handler = handler
            session.feature(SendsPrompts).send(Prompt)
            assertEquals(3, fixture.wire.peers.size)
            assertEquals(1, fixture.requests("thread/name/set").size)
            assertSame(fixture.wire.peers.last(), fixture.wire.origin(fixture.requests("turn/start").single()))
            fixture.runtime.close()
        }

    @Test
    fun `cancelling a cold resume closes its candidate and the next send retries with the same ref`() = runTest {
        val tools = PolicyTools().apply { current = policy(1) }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        val handler = fixture.wire.handler
        fixture.wire.handler = { if (it.text("method") != "thread/resume") handler(it) }
        tools.current = policy(2, "web_search")
        val sending = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        assertEquals(1, fixture.requests("thread/resume").size)
        sending.cancel()
        runCurrent()
        assertTrue(fixture.wire.peers.all { it.isClosed })
        assertIs<ActiveSessionState.Ready>(session.state.value)
        fixture.wire.handler = handler
        session.feature(SendsPrompts).send(Prompt)
        assertTrue(fixture.requests("thread/resume").all { it.obj("params").text("threadId") == session.ref.nativeId })
        fixture.runtime.close()
    }

    @Test
    fun `conflicting candidate config leaves the previous process and history intact`() = runTest {
        val tools = PolicyTools().apply { current = policy(1) }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        val before = session.feature(SessionHistory).page()
        val handler = fixture.wire.handler
        fixture.wire.handler = { message ->
            if (message.text("method") == "config/read" && fixture.wire.origin(message).off.isShellDisabled) {
                fixture.wire.reply(message, json("config" to fixture.nativeConfig))
            } else {
                handler(message)
            }
        }
        tools.current = policy(2, "shell")
        assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertFalse(fixture.wire.peers.first().isClosed)
        assertTrue(fixture.wire.peers.last().isClosed)
        assertTrue(fixture.requests("thread/resume").isEmpty())
        assertTrue(fixture.requests("turn/start").isEmpty())
        assertEquals(before, session.feature(SessionHistory).page())
        fixture.wire.handler = handler
        session.feature(SendsPrompts).send(Prompt)
        assertIs<ActiveSessionState.Running>(session.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `changing policy during resume discards stale candidate before sending`() = runTest {
        val tools = PolicyTools().apply { current = policy(1) }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        val handler = fixture.wire.handler
        fixture.wire.handler = { message ->
            if (message.text("method") == "thread/resume") tools.current = policy(3, "shell", "web_search")
            handler(message)
        }
        tools.current = policy(2, "shell")
        session.feature(SendsPrompts).send(Prompt)
        assertEquals(3, fixture.wire.peers.size)
        assertTrue(fixture.wire.peers.take(2).all { it.isClosed })
        assertEquals(CodexNativeOff(true, true), fixture.wire.peers.last().off)
        assertSame(fixture.wire.peers.last(), fixture.wire.origin(fixture.requests("turn/start").single()))
        fixture.runtime.close()
    }

    @Test
    fun `continuous generation churn refuses after bounded retries without submitting`() = runTest {
        val tools = PolicyTools().apply { current = policy(1) }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        val handler = fixture.wire.handler
        var generation = 2L
        fixture.wire.handler = { message ->
            if (message.text("method") == "thread/resume") tools.current = policy(++generation, "shell")
            handler(message)
        }
        tools.current = policy(generation, "shell")
        assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertEquals(3, fixture.requests("thread/resume").size)
        assertTrue(fixture.wire.peers.all { it.isClosed })
        assertTrue(fixture.requests("turn/start").isEmpty())
        assertIs<ActiveSessionState.Ready>(session.state.value)
        fixture.wire.handler = handler
        session.feature(SendsPrompts).send(Prompt)
        assertEquals(1, fixture.requests("turn/start").size)
        fixture.runtime.close()
    }

    @Test
    fun `cancelled empty thread materialization keeps old process usable and closes candidate`() = runTest {
        val tools = PolicyTools().apply { current = policy(1) }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        val handler = fixture.wire.handler
        fixture.wire.handler = { if (it.text("method") != "thread/name/set") handler(it) }
        tools.current = policy(2, "shell")
        val sending = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        assertEquals(1, fixture.requests("thread/name/set").size)
        sending.cancel()
        runCurrent()
        assertFalse(fixture.wire.peers.first().isClosed)
        assertTrue(fixture.wire.peers.last().isClosed)
        assertTrue(fixture.requests("thread/resume").isEmpty())
        assertTrue(fixture.requests("turn/start").isEmpty())
        fixture.wire.handler = handler
        session.feature(SendsPrompts).send(Prompt)
        assertIs<ActiveSessionState.Running>(session.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `policy change at final confirmation is a known rejection and next submission can recover`() = runTest {
        val tools = PolicyTools().apply { current = policy(1) }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.complete()
        runCurrent()
        var calls = 0
        tools.lookup = {
            if (++calls == 1) policy(1) else policy(2, "shell")
        }
        assertFailsWith<EngineException> {
            session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("rejected")))
        }
        assertEquals(1, fixture.requests("turn/start").size)
        assertIs<ActiveSessionState.Ready>(session.state.value)
        session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("retry")))
        assertEquals(2, fixture.requests("turn/start").size)
        fixture.runtime.close()
    }

    @Test
    fun `cold resume retains history despite an obsolete read failure`() = runTest {
        val tools = PolicyTools().apply { current = policy(1) }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        val item = json("id" to "answer".json(), "type" to "agentMessage".json(), "text" to "Retained".json())
        fixture.event("item/completed", "turnId" to "native-turn".json(), "item" to item)
        fixture.complete()
        runCurrent()
        val history = session.feature(SessionHistory)
        val before = history.page()
        assertEquals(1, before.items.size)
        assertEquals(HistoryCoverage.Complete, before.coverage)
        fixture.resumedTurns = JsonArray(
            listOf(
                json(
                    "id" to "native-turn".json(),
                    "status" to "completed".json(),
                    "itemsView" to "full".json(),
                    "items" to JsonArray(listOf(item)),
                ),
            ),
        )
        val handler = fixture.wire.handler
        fixture.wire.handler = { if (it.text("method") != "thread/read") handler(it) }
        val attaching = async { fixture.runtime.attach(session.ref, ResumeSessionRequest(fixture.target)) }
        runCurrent()
        tools.current = policy(2, "shell")
        session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("second")))
        val attached = attaching.await()
        assertEquals(session.ref, attached.ref)
        assertEquals(before.items, history.page().items)
        assertEquals(HistoryCoverage.Complete, history.page().coverage)
        assertIs<ActiveSessionState.Running>(attached.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `questionnaire after cold resume answers on the replacement connection exactly once`() = runTest {
        val tools = PolicyTools().apply { current = policy(1) }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        tools.current = policy(2, "shell")
        session.feature(SendsPrompts).send(Prompt)
        fixture.event(
            "item/tool/requestUserInput",
            "turnId" to "native-turn".json(),
            "itemId" to "question-tool".json(),
            "questions" to JsonArray(
                listOf(
                    json(
                        "id" to "details".json(),
                        "header" to "Details".json(),
                        "question" to "Which details?".json(),
                    ),
                ),
            ),
            id = JsonPrimitive(900),
        )
        runCurrent()
        val question = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests.single()
        session.feature(RequestsPermissions).respond(
            PermissionDecision(
                question.turn,
                question.id,
                question.options.first { !it.isSkip }.id,
                PermissionAnswer.Text("Answer"),
            ),
        )
        runCurrent()
        fixture.complete()
        runCurrent()
        val reply = fixture.wire.written.single { it["id"] == JsonPrimitive(900) }
        assertSame(fixture.wire.peers.last(), fixture.wire.origin(reply))
        assertEquals(JsonArray(listOf("Answer".json())), reply.obj("result").obj("answers").obj("details")["answers"])
        fixture.runtime.close()
    }

    @Test
    fun `cancelled final policy lookup releases pending submission and keeps the session reusable`() = runTest {
        val tools = PolicyTools().apply { current = policy(1) }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.complete()
        runCurrent()
        var calls = 0
        tools.lookup = {
            if (++calls == 2) throw CancellationException("Provider cancelled")
            policy(1)
        }
        val sending = async {
            assertFailsWith<CancellationException> {
                session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("cancelled")))
            }
        }
        runCurrent()
        assertTrue(sending.isCompleted)
        sending.await()
        assertEquals(1, fixture.requests("turn/start").size)
        assertIs<ActiveSessionState.Ready>(session.state.value)
        session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("retry")))
        assertEquals(2, fixture.requests("turn/start").size)
        fixture.runtime.close()
    }

    private fun policy(generation: Long, vararg off: String): ResolvedToolPolicy =
        ResolvedToolPolicy(nativeOff = off.toSet(), generation = generation)

    private fun Fixture.requests(method: String): List<JsonObject> = wire.written.filter { it.text("method") == method }

    private suspend fun Fixture.complete() = event(
        "turn/completed",
        "turn" to json("id" to "native-turn".json(), "status" to "completed".json()),
    )

    private class PolicyTools : ProfileAgentTools by NoAgentTools {
        var current: ResolvedToolPolicy? = ResolvedToolPolicy()
        var lookup: suspend (ToolPolicyScope) -> ResolvedToolPolicy? = { current }
        override suspend fun nativeToolsForExecution(scope: ToolPolicyScope): ResolvedToolPolicy? = lookup(scope)
    }
}
