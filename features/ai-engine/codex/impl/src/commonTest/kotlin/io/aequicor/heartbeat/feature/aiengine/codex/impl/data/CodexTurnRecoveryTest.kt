@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    fun `cold resume retains unresolved turn while its recorded owner cannot be proven gone`() = runTest {
        for (snapshot in listOf(null, JsonArray(emptyList()), JsonArray(listOf(native("unknown"))))) {
            val records = MemoryCodexTurnRecords()
            val (first, _) = owned(records)
            val original = first.open()
            val turn = original.feature(SendsPrompts).send(Prompt)
            first.runtime.close()
            val (second, launch) = owned(records) { false }
            second.resumedTurns = snapshot
            val restored = second.runtime.attach(original.ref, ResumeSessionRequest(second.target))
            runCurrent()
            assertEquals(turn, assertIs<ActiveSessionState.Unavailable>(restored.state.value).activeTurn?.id)
            assertEquals(1, launch.stops)
            second.threadTurns = listOf(native("inProgress"))
            second.event("turn/completed", "turn" to json("id" to "foreign".json(), "status" to "completed".json()))
            runCurrent()
            assertEquals(turn, assertIs<ActiveSessionState.Unavailable>(restored.state.value).activeTurn?.id)
            assertFailsWith<EngineException> {
                restored.feature(SendsPrompts).send(Prompt.copy(id = RequestId("recovery")))
            }
            assertFalse(second.wire.written.any { it.text("method") == "turn/start" })
            assertEquals(turn, records.get(original.ref)?.active?.turn?.id)
            second.runtime.close()
        }
    }

    @Test
    fun `restart during an ordinary chat turn becomes ready after its owner's proven exit`() = runTest {
        for (snapshot in listOf(null, JsonArray(listOf(native("interrupted"))))) {
            val records = MemoryCodexTurnRecords()
            val (first, _) = owned(records)
            val original = first.open()
            val turn = original.feature(SendsPrompts).send(Prompt)
            first.runtime.close()
            val (second, launch) = owned(records)
            second.resumedTurns = snapshot
            val restored = second.runtime.attach(original.ref, ResumeSessionRequest(second.target))
            runCurrent()
            val settled = assertIs<ActiveSessionState.Ready>(restored.state.value).lastTurn
            assertEquals(turn, settled?.id)
            assertEquals(TurnOutcome.Unknown, settled?.outcome)
            assertEquals(1, launch.stops)
            assertTrue(records.get(original.ref)?.last?.isProcessStopped == true)
            val next = restored.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next")))
            assertEquals(next, assertIs<ActiveSessionState.Running>(restored.state.value).turn.id)
            second.runtime.close()
        }
    }

    @Test
    fun `restored turn without any recorded owner closes as unknown`() = runTest {
        val records = MemoryCodexTurnRecords()
        val first = Fixture(this, turns = records).initialized()
        val original = first.open()
        val turn = original.feature(SendsPrompts).send(Prompt)
        first.runtime.close()
        assertNull(records.get(original.ref)?.active?.processOwner)
        val second = Fixture(this, turns = records).initialized()
        second.resumedTurns = JsonArray(listOf(native("interrupted")))
        val restored = second.runtime.attach(original.ref, ResumeSessionRequest(second.target))
        runCurrent()
        val settled = assertIs<ActiveSessionState.Ready>(restored.state.value).lastTurn
        assertEquals(turn, settled?.id)
        assertEquals(TurnOutcome.Unknown, settled?.outcome)
        assertEquals(settled, records.get(original.ref)?.last?.turn)
        restored.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next")))
        second.runtime.close()
    }

    @Test
    fun `turn closed before its native id was bound settles through its owner after restart`() = runTest {
        val records = MemoryCodexTurnRecords()
        val (first, _) = owned(records)
        first.onTurn = {}
        val original = first.open()
        val sending = async { assertFailsWith<EngineException> { original.feature(SendsPrompts).send(Prompt) } }
        runCurrent()
        first.runtime.close()
        sending.await()
        val unbound = assertNotNull(records.get(original.ref)?.active)
        assertNull(unbound.nativeId)
        val (second, launch) = owned(records)
        val restored = second.runtime.attach(original.ref, ResumeSessionRequest(second.target))
        runCurrent()
        val settled = assertIs<ActiveSessionState.Ready>(restored.state.value).lastTurn
        assertEquals(unbound.turn.id, settled?.id)
        assertEquals(TurnOutcome.Unknown, settled?.outcome)
        assertEquals(1, launch.stops)
        restored.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next")))
        second.runtime.close()
    }

    @Test
    fun `settled chat reopens and continues after a login to another account`() = runTest {
        val records = MemoryCodexTurnRecords()
        val first = Fixture(this, turns = records).initialized()
        val original = first.open()
        val turn = original.feature(SendsPrompts).send(Prompt)
        first.event("turn/completed", "turn" to native("completed"))
        runCurrent()
        first.runtime.close()
        val second = Fixture(this, turns = records)
        second.account = json("type" to "chatgpt".json(), "email" to "other@example.invalid".json())
        second.initialized()
        val restored = second.runtime.attach(original.ref, ResumeSessionRequest(second.target))
        assertEquals(turn, assertIs<ActiveSessionState.Ready>(restored.state.value).lastTurn?.id)
        val next = restored.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next")))
        assertEquals(next, records.get(original.ref)?.active?.turn?.id)
        second.runtime.close()
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

    /** Execution processes record an exact owner; [onStop] decides whether its exit can be proven. */
    private suspend fun TestScope.owned(
        records: CodexTurnRecords,
        onStop: suspend () -> Boolean = { true },
    ): Pair<Fixture, OwnedStopLaunch> {
        val launch = OwnedStopLaunch().also { it.onStop = onStop }
        val fixture = Fixture(this, turns = records, launch = launch).also { launch.wire = it.wire }
        return fixture.initialized() to launch
    }

    private fun native(status: String): JsonObject = json(
        "id" to "native-turn".json(),
        "status" to status.json(),
        "itemsView" to "full".json(),
        "items" to JsonArray(
            listOf(json("id" to "answer".json(), "type" to "agentMessage".json(), "text" to "Answer".json())),
        ),
    )

    @Test
    fun `completion during adoption is discovered by the second native snapshot`() = runTest {
        val before = Fixture(this)
        val original = before.open()
        original.feature(SendsPrompts).send(Prompt)
        val receipt = assertNotNull(original.feature(RestoresSessionTurns).checkpoint(Prompt.id))
        val after = Fixture(this)
        after.threadTurns = listOf(json("id" to "native-turn".json(), "status" to "inProgress".json()))
        val resumed = after.runtime.attach(original.ref, ResumeSessionRequest(after.target))
        val handler = after.wire.handler
        after.wire.handler = { message ->
            handler(message)
            if (message.text("method") == "thread/read") {
                after.threadTurns = listOf(json("id" to "native-turn".json(), "status" to "completed".json()))
                after.event("turn/completed", "turn" to after.threadTurns.single())
            }
        }
        assertEquals(
            TurnInspection.Observed(Prompt.id, TurnOutcome.Completed),
            resumed.feature(RestoresSessionTurns).inspect(receipt),
        )
        assertIs<ActiveSessionState.Ready>(resumed.state.value)
    }

    @Test
    fun `native receipt survives recreation and recovers completed outcome`() = runTest {
        val before = Fixture(this)
        val original = before.open()
        original.feature(SendsPrompts).send(Prompt)
        val receipt = assertNotNull(original.feature(RestoresSessionTurns).checkpoint(Prompt.id))
        val after = Fixture(this)
        after.threadTurns = listOf(json("id" to "native-turn".json(), "status" to "completed".json()))
        val resumed = after.runtime.attach(original.ref, ResumeSessionRequest(after.target))
        assertIs<ActiveSessionState.Ready>(resumed.state.value)
        assertEquals(
            TurnInspection.Observed(Prompt.id, TurnOutcome.Completed),
            resumed.feature(RestoresSessionTurns).inspect(receipt),
        )
    }

    @Test
    fun `only a receipt matched running native turn may be adopted`() = runTest {
        val before = Fixture(this)
        val original = before.open()
        original.feature(SendsPrompts).send(Prompt)
        val receipt = assertNotNull(original.feature(RestoresSessionTurns).checkpoint(Prompt.id))
        val after = Fixture(this)
        after.threadTurns = listOf(json("id" to "native-turn".json(), "status" to "inProgress".json()))
        val resumed = after.runtime.attach(original.ref, ResumeSessionRequest(after.target))
        val recovery = resumed.feature(RestoresSessionTurns)
        assertEquals(TurnInspection.Unknown, recovery.inspect(null))
        assertEquals(TurnInspection.Observed(Prompt.id, null), recovery.inspect(receipt))
        assertEquals(Prompt.id, assertIs<ActiveSessionState.Running>(resumed.state.value).turn.request)
    }
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
