@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexTurnRecoveryTest {
    @Test
    fun `new runtime restores request and answer markers without sending`() = runTest {
        for (status in listOf("completed", "failed")) {
            val records = MemoryCodexTurnRecords()
            val first = Fixture(this, turns = records).initialized()
            val original = first.open()
            val turn = original.feature(SendsPrompts).send(Prompt)
            first.runtime.close()
            assertEquals(turn, records.get(original.ref)?.active?.turn?.id)

            val second = Fixture(this, turns = records).initialized()
            second.resumedTurns = JsonArray(listOf(native(status)))
            second.resumedHistoryMode = "paginated"
            val recovered = second.runtime.attach(original.ref, ResumeSessionRequest(second.target))
            val result = assertIs<ActiveSessionState.Ready>(recovered.state.value).lastTurn
            assertEquals(turn, result?.id)
            assertEquals(Prompt.id, result?.request)
            when (status) {
                "completed" -> assertEquals(TurnOutcome.Completed, result?.outcome)
                "failed" -> assertIs<TurnOutcome.Failed>(result?.outcome)
            }
            assertEquals(turn, recovered.feature(SessionHistory).page().items.single().info.turn)
            assertEquals(result, records.get(original.ref)?.last?.turn)
            assertNull(records.get(original.ref)?.active)
            assertFalse(second.wire.written.any { it.text("method") == "turn/start" })
        }
    }

    @Test
    fun `persisted terminal survives an empty native projection after restart`() = runTest {
        val records = MemoryCodexTurnRecords()
        val first = Fixture(this, turns = records).initialized()
        val original = first.open()
        val turn = original.feature(SendsPrompts).send(Prompt)
        first.event("turn/completed", "turn" to native("completed"))
        runCurrent()
        first.runtime.close()
        val second = Fixture(this, turns = records).initialized()
        val restored = second.runtime.attach(original.ref, ResumeSessionRequest(second.target))
        val result = assertIs<ActiveSessionState.Ready>(restored.state.value).lastTurn
        assertEquals(turn, result?.id)
        assertEquals(TurnOutcome.Completed, result?.outcome)
        assertFalse(second.wire.written.any { it.text("method") == "turn/start" })
    }

    @Test
    fun `cold resume retains unresolved turn without adopting the old process`() = runTest {
        val records = MemoryCodexTurnRecords()
        val first = Fixture(this, turns = records).initialized()
        val original = first.open()
        val turn = original.feature(SendsPrompts).send(Prompt)
        first.runtime.close()
        for (snapshot in listOf(null, JsonArray(emptyList()), JsonArray(listOf(native("unknown"))))) {
            val second = Fixture(this, turns = records).initialized()
            second.resumedTurns = snapshot
            val restored = second.runtime.attach(original.ref, ResumeSessionRequest(second.target))
            assertEquals(turn, assertIs<ActiveSessionState.Unavailable>(restored.state.value).activeTurn?.id)
            second.threadTurns = listOf(native("inProgress"))
            second.event("turn/completed", "turn" to json("id" to "foreign".json(), "status" to "completed".json()))
            runCurrent()
            assertEquals(turn, assertIs<ActiveSessionState.Unavailable>(restored.state.value).activeTurn?.id)
            assertFailsWith<EngineException> {
                restored.feature(SendsPrompts).send(Prompt.copy(id = RequestId("recovery")))
            }
            assertFalse(second.wire.written.any { it.text("method") == "turn/start" })
            second.runtime.close()
        }
    }

    @Test
    fun `changed native store or account cannot resume an owned request`() = runTest {
        val records = MemoryCodexTurnRecords()
        val first = Fixture(this, turns = records).initialized()
        val original = first.open()
        original.feature(SendsPrompts).send(Prompt)
        first.runtime.close()
        for (changeHome in listOf(true, false)) {
            val second = Fixture(this, turns = records)
            if (!changeHome) {
                second.account = json("type" to "chatgpt".json(), "email" to "other@example.invalid".json())
            }
            second.initialized(if (changeHome) "/other/home" else "/owned/codex")
            assertFailsWith<EngineException> {
                second.runtime.attach(original.ref, ResumeSessionRequest(second.target))
            }
            assertFalse(second.wire.written.any { it.text("method") in setOf("thread/resume", "turn/start") })
        }
    }

    @Test
    fun `native acceptance waits for its durable correlation`() = runTest {
        val records = GatedTurnRecords()
        val fixture = Fixture(this, turns = records).initialized()
        val session = fixture.open()
        records.beforeWrite = { if (it.active?.nativeId != null) records.gate.await() }
        val sending = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        assertFalse(sending.isCompleted)
        assertIs<ActiveSessionState.Submitting>(session.state.value)
        assertNull(records.get(session.ref)?.active?.nativeId)
        records.gate.complete(Unit)
        val turn = sending.await()
        assertEquals(turn, records.get(session.ref)?.active?.turn?.id)
        assertEquals("native-turn", records.get(session.ref)?.active?.nativeId)
    }

    @Test
    fun `submission waits for the durable begin before turn start`() = runTest {
        val records = GatedTurnRecords()
        val fixture = Fixture(this, turns = records).initialized()
        val session = fixture.open()
        records.beforeWrite = { records.gate.await() }
        val sending = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        assertFalse(sending.isCompleted)
        assertFalse(fixture.wire.written.any { it.text("method") == "turn/start" })
        records.gate.complete(Unit)
        sending.await()
        assertTrue(fixture.wire.written.any { it.text("method") == "turn/start" })
    }

    private suspend fun Fixture.initialized(home: String = "/owned/codex"): Fixture = apply {
        codexHome = home
        rpc.initialize()
    }

    private fun native(status: String): JsonObject = json(
        "id" to "native-turn".json(),
        "status" to status.json(),
        "itemsView" to "full".json(),
        "items" to JsonArray(
            listOf(json("id" to "answer".json(), "type" to "agentMessage".json(), "text" to "Answer".json())),
        ),
    )
}

/** Delays writes before the atomic in-memory update, preserving the production store's transaction semantics. */
internal class GatedTurnRecords : CodexTurnRecords {
    private val delegate = MemoryCodexTurnRecords()
    val gate = CompletableDeferred<Unit>()
    var beforeWrite: suspend (CodexTurnSnapshot) -> Unit = {}
    override suspend fun get(ref: SessionRef): CodexTurnSnapshot? = delegate.get(ref)
    override suspend fun update(
        ref: SessionRef,
        transform: (CodexTurnSnapshot?) -> CodexTurnSnapshot,
    ): CodexTurnSnapshot {
        beforeWrite(transform(delegate.get(ref)))
        return delegate.update(ref, transform)
    }
}
