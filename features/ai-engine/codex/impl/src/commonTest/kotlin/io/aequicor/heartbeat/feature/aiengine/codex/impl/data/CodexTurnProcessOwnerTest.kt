@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CodexTurnProcessOwnerTest {
    @Test
    fun `submission persists execution owner before native send and ignores metadata owner`() = runTest {
        val records = GatedTurnRecords()
        val fixture = Fixture(this, turns = records)
        fixture.wire.owner = owner("metadata", 1)
        val session = fixture.open()
        val execution = fixture.wire.peers.last()
        execution.owner = owner("execution", 2)
        records.beforeWrite = { if (it.active != null) records.gate.await() }
        var persistedAtSend: CodexExecutionOwner? = null
        fixture.onTurn = {
            persistedAtSend = records.get(session.ref)?.active?.processOwner
            fixture.wire.reply(it, json("turn" to json("id" to "native-turn".json())))
        }
        val sending = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        assertFalse(fixture.wire.written.any { it.text("method") == "turn/start" })
        records.gate.complete(Unit)
        sending.await()
        assertEquals(execution.owner, persistedAtSend)
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        assertEquals(execution.owner, records.get(session.ref)?.last?.processOwner)
        assertEquals(TurnOutcome.Completed, records.get(session.ref)?.last?.turn?.outcome)
    }

    @Test
    fun `process inspection completes before journal begin or native submission`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val capture = CompletableDeferred<CodexExecutionOwner?>()
        fixture.wire.peers.last().captureOwner = { capture.await() }
        val sending = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        assertNull(fixture.environment.turns.get(session.ref))
        assertFalse(fixture.wire.written.any { it.text("method") == "turn/start" })
        capture.complete(owner("execution", 2))
        sending.await()
        assertEquals(owner("execution", 2), fixture.environment.turns.get(session.ref)?.active?.processOwner)
    }

    @Test
    fun `legacy records and unsupported transport retain missing stop evidence`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        val record = assertNotNull(fixture.environment.turns.get(session.ref)?.active)
        assertNull(record.processOwner)
        val raw = Json.encodeToString(record)
        assertFalse(raw.contains("processOwner"))
        assertNull(Json.decodeFromString<CodexTurnRecord>(raw).processOwner)
    }

    private fun owner(id: String, pid: Long): CodexExecutionOwner =
        CodexExecutionOwner(id, CodexProcessIdentity(pid, "2026-10-06T00:00:00Z"))
}
