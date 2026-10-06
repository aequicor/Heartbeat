package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioHelperAttemptsTest {
    private val helper = HelperId("helper")
    private val prompt = HelperPrompt(RequestId("R"), "private prompt")
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
    private val terminal = StudioHelperTerminal(
        session,
        TurnId("turn"),
        StudioHelperTerminalOutcome.Completed,
        "private answer",
    )

    @Test
    fun `old receipts deduplicate immutable requests after newer attempts and restart`() = runTest {
        val stores = ChecklistTestStores()
        val journal = StudioHelperAttempts(stores)
        assertTrue(journal.prepare(helper, prompt).isNew)
        assertFalse(journal.prepare(helper, prompt).isNew)
        assertFailsWith<IllegalStateException> { journal.prepare(helper, prompt.copy(text = "changed")) }
        assertFailsWith<IllegalStateException> { journal.prepare(helper, prompt.copy(isRecovery = true)) }
        assertFailsWith<IllegalStateException> { journal.prepare(helper, prompt.copy(request = RequestId("R2"))) }
        assertTrue(journal.begin(helper, prompt.request))
        assertFalse(journal.begin(helper, prompt.request))
        journal.terminal(helper, prompt.request, terminal)
        val next = prompt.copy(request = RequestId("R2"), isRecovery = true)
        assertTrue(journal.prepare(helper, next).isNew)
        val restarted = StudioHelperAttempts(stores)
        assertFalse(restarted.prepare(helper, prompt).isNew)
        assertEquals(terminal, restarted.receipt(helper, prompt.request)?.terminal)
        assertEquals(next.request, restarted.unresolved(helper))
        assertFalse(restarted.prepare(helper, next).isNew)
    }

    @Test
    fun `cancel before prompt arrival persists a tombstone that prevents every late send`() = runTest {
        val stores = ChecklistTestStores()
        val journal = StudioHelperAttempts(stores)
        assertEquals(StudioHelperPhase.NotSubmitted, journal.cancelBeforeSubmission(helper, prompt.request).phase)
        val restarted = StudioHelperAttempts(stores)
        assertEquals(StudioHelperPhase.NotSubmitted, restarted.prepare(helper, prompt).receipt.phase)
        assertFalse(restarted.begin(helper, prompt.request))
        assertFalse(restarted.prepare(helper, prompt.copy(text = "arbitrary delayed text")).isNew)
        assertNull(restarted.unresolved(helper))
        assertFailsWith<IllegalStateException> { restarted.accepted(helper, prompt.request, session, terminal.turn) }
        assertFailsWith<IllegalStateException> { restarted.terminal(helper, prompt.request, terminal) }
    }

    @Test
    fun `cancellation wins the storage barrier before begin and wakes preparation observer`() = runTest {
        val stores = AttemptStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        val gate = StudioHelperSubmission(journal, helper, prompt.request)
        val revoked = async { gate.awaitRevocation() }
        val write = CompletableDeferred<Unit>()
        stores.beforeWrite = { write.await() }
        val cancelled = async { gate.cancel() }
        runCurrent()
        val begin = async { journal.begin(helper, prompt.request) }
        runCurrent()
        assertFalse(begin.isCompleted)
        assertFalse(cancelled.isCompleted)
        write.complete(Unit)
        assertTrue(cancelled.await())
        assertFalse(begin.await())
        assertNotNull(revoked.await())
        assertTrue(gate.isCancelled)
        assertFailsWith<CancellationException> { gate.begin() }
    }

    @Test
    fun `begin wins the storage barrier and cancellation cannot claim NotSubmitted`() = runTest {
        val stores = AttemptStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        val write = CompletableDeferred<Unit>()
        stores.beforeWrite = { write.await() }
        val begin = async { journal.begin(helper, prompt.request) }
        runCurrent()
        val cancel = async { journal.cancelBeforeSubmission(helper, prompt.request) }
        runCurrent()
        assertFalse(cancel.isCompleted)
        write.complete(Unit)
        assertTrue(begin.await())
        assertEquals(StudioHelperPhase.Submitting, cancel.await().phase)
        val restarted = StudioHelperAttempts(stores)
        assertFalse(restarted.prepare(helper, prompt).isNew)
        assertFalse(restarted.begin(helper, prompt.request))
        assertEquals(prompt.request, restarted.unresolved(helper))
    }

    @Test
    fun `failed or interrupted journal write never grants native submission`() = runTest {
        val stores = AttemptStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        stores.beforeWrite = { error("disk") }
        val gate = StudioHelperSubmission(journal, helper, prompt.request)
        assertFailsWith<IllegalStateException> { gate.begin() }
        assertEquals(StudioHelperPhase.Preparing, journal.receipt(helper, prompt.request)?.phase)
        stores.beforeWrite = {}
        stores.afterWrite = { throw CancellationException("ack lost") }
        assertFailsWith<CancellationException> { gate.begin() }
        stores.afterWrite = {}
        assertEquals(StudioHelperPhase.Submitting, journal.receipt(helper, prompt.request)?.phase)
        assertFalse(journal.begin(helper, prompt.request))
        assertEquals(StudioHelperPhase.Submitting, journal.cancelBeforeSubmission(helper, prompt.request).phase)
    }

    @Test
    fun `failed cancellation cannot report safety and committed tombstone survives lost acknowledgement`() = runTest {
        val stores = AttemptStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        stores.beforeWrite = { error("disk") }
        val gate = StudioHelperSubmission(journal, helper, prompt.request)
        assertFailsWith<IllegalStateException> { gate.cancel() }
        assertFalse(gate.isCancelled)
        stores.beforeWrite = {}
        stores.afterWrite = { throw CancellationException("ack lost") }
        assertFailsWith<CancellationException> { gate.cancel() }
        stores.afterWrite = {}
        assertFalse(journal.begin(helper, prompt.request))
        assertTrue(gate.cancel())
        assertTrue(gate.isCancelled)
    }

    @Test
    fun `native evidence is exact immutable and terminal result survives late acceptance`() = runTest {
        val journal = StudioHelperAttempts(ChecklistTestStores())
        journal.prepare(helper, prompt)
        journal.begin(helper, prompt.request)
        journal.accepted(helper, prompt.request, session, terminal.turn)
        assertEquals(StudioHelperPhase.Accepted, journal.cancelBeforeSubmission(helper, prompt.request).phase)
        assertFailsWith<IllegalStateException> {
            journal.terminal(helper, prompt.request, terminal.copy(turn = TurnId("different")))
        }
        assertFailsWith<IllegalStateException> {
            journal.accepted(helper, prompt.request, session.copy(nativeId = "other"), terminal.turn)
        }
        assertFailsWith<IllegalStateException> { journal.terminal(helper, RequestId("other"), terminal) }
        journal.terminal(helper, prompt.request, terminal)
        journal.accepted(helper, prompt.request, session, terminal.turn)
        journal.terminal(helper, prompt.request, terminal)
        val receipt = requireNotNull(journal.receipt(helper, prompt.request))
        assertEquals(StudioHelperPhase.Terminal, receipt.phase)
        assertEquals(HelperOutcome.Completed, receipt.terminal?.result(prompt.request)?.outcome)
        assertEquals(prompt.request, receipt.terminal?.result(prompt.request)?.request)
        assertNull(journal.unresolved(helper))
        assertFailsWith<IllegalStateException> {
            journal.terminal(helper, prompt.request, terminal.copy(answer = "different"))
        }
        assertFalse(receipt.toString().contains("private"))
        assertFalse(terminal.toString().contains("private"))
    }

    @Test
    fun `unknown terminal survives restart and admits a distinct recovery request`() = runTest {
        val stores = ChecklistTestStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        journal.begin(helper, prompt.request)
        journal.accepted(helper, prompt.request, session, terminal.turn)
        journal.terminal(
            helper,
            prompt.request,
            terminal.copy(outcome = StudioHelperTerminalOutcome.Unknown, answer = ""),
        )
        val restored = StudioHelperAttempts(stores)
        val result = requireNotNull(restored.receipt(helper, prompt.request)?.terminal).result(prompt.request)
        assertEquals(HelperOutcome.Unknown, result.outcome)
        assertEquals(terminal.turn, result.turn)
        assertEquals(prompt.request, result.request)
        assertNull(restored.unresolved(helper))
        assertFalse(restored.prepare(helper, prompt).isNew)
        assertFalse(restored.begin(helper, prompt.request))
        assertTrue(restored.prepare(helper, prompt.copy(request = RequestId("recovery"), isRecovery = true)).isNew)
    }

    @Test
    fun `unreadable receipts fail closed without exposing stored text`() = runTest {
        val stores = ChecklistTestStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        val values = stores.stores.getValue(HelperAttemptsSpec).values
        values.value = values.value.mapValues { "invalid private answer" }
        val failure = assertFailsWith<IllegalStateException> { journal.begin(helper, prompt.request) }
        assertFalse(failure.message.orEmpty().contains("private"))
        assertNull(failure.cause)
        assertFailsWith<IllegalStateException> { journal.cancelBeforeSubmission(helper, prompt.request) }
    }

    @Test
    fun `same request id belongs independently to each helper`() = runTest {
        val journal = StudioHelperAttempts(ChecklistTestStores())
        val other = HelperId("other")
        journal.cancelBeforeSubmission(helper, prompt.request)
        assertTrue(journal.prepare(other, prompt).isNew)
        assertTrue(journal.begin(other, prompt.request))
        assertFalse(journal.begin(helper, prompt.request))
    }
}

internal class AttemptStores(private val delegate: ChecklistTestStores = ChecklistTestStores()) :
    DataStores by delegate {
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}

    override fun keyValue(spec: KeyValueSpec): KeyValueStore {
        val values = delegate.keyValue(spec)
        return object : KeyValueStore by values {
            override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
                beforeWrite()
                values.set(key, value, retention)
                afterWrite()
            }
        }
    }
}
