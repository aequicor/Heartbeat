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
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
    fun `lost prepared target acknowledgement is cleaned before leaving local preparation`() = runTest {
        val stores = AttemptStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        stores.afterWrite = {
            stores.afterWrite = {}
            throw CancellationException("lost binding ACK")
        }
        val gate = StudioHelperSubmission(journal, helper, prompt.request, admissions = emptyHelperAdmissions())
        assertFailsWith<CancellationException> {
            gate.withPreparation(session) { error("must not prepare") }
        }
        assertTrue(gate.isCancelled)
        val receipt = assertNotNull(journal.receipt(helper, prompt.request))
        assertEquals(session, receipt.preparedSession)
        assertEquals(StudioHelperPhase.NotSubmitted, receipt.phase)
    }

    @Test
    fun `only exact local claim may retire a submitting attempt after lost acknowledgement`() = runTest {
        val stores = AttemptStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        journal.bindSession(helper, prompt.request, session)
        stores.afterWrite = { throw CancellationException("lost begin ACK") }
        assertFailsWith<CancellationException> { journal.begin(helper, prompt.request, "sender") }
        stores.afterWrite = {}
        assertEquals(StudioHelperPhase.Submitting, journal.cancelBeforeSubmission(helper, prompt.request).phase)
        assertFalse(journal.cancelBeforeNative(helper, prompt.request, session, "another sender"))
        assertFalse(journal.cancelBeforeNative(helper, prompt.request, session.copy(nativeId = "wrong"), "sender"))
        assertTrue(journal.cancelBeforeNative(helper, prompt.request, session, "sender"))
        assertEquals(
            StudioHelperPhase.NotSubmitted,
            StudioHelperAttempts(stores).receipt(helper, prompt.request)?.phase,
        )
        assertFalse(journal.begin(helper, prompt.request, "late sender"))
    }

    @Test
    fun `local cleanup never retires an accepted native turn`() = runTest {
        val journal = StudioHelperAttempts(ChecklistTestStores())
        journal.prepare(helper, prompt)
        journal.bindSession(helper, prompt.request, session)
        journal.begin(helper, prompt.request, "sender")
        journal.accepted(helper, prompt.request, session, terminal.turn)
        assertFalse(journal.cancelBeforeNative(helper, prompt.request, session, "sender"))
        assertEquals(StudioHelperPhase.Accepted, journal.receipt(helper, prompt.request)?.phase)
    }

    @Test
    fun `local preparation cleanup retires its persisted begin even when acknowledgement is cancelled`() = runTest {
        val stores = AttemptStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        val gate = StudioHelperSubmission(journal, helper, prompt.request, admissions = emptyHelperAdmissions())
        assertFailsWith<CancellationException> {
            gate.withPreparation(session) {
                stores.afterWrite = {
                    stores.afterWrite = {}
                    throw CancellationException("lost begin ACK")
                }
                gate.begin()
                error("must not enter native send")
            }
        }
        assertTrue(gate.isCancelled)
        assertEquals(StudioHelperPhase.NotSubmitted, journal.receipt(helper, prompt.request)?.phase)
    }

    @Test
    fun `after native ownership cancellation retains uncertainty for reconciliation`() = runTest {
        val journal = StudioHelperAttempts(ChecklistTestStores())
        journal.prepare(helper, prompt)
        val gate = StudioHelperSubmission(journal, helper, prompt.request, admissions = emptyHelperAdmissions())
        assertFailsWith<CancellationException> {
            gate.withPreparation(session) {
                gate.begin()
                throw CancellationException("native ACK unknown")
            }
        }
        assertFalse(gate.isCancelled)
        assertEquals(StudioHelperPhase.Submitting, journal.receipt(helper, prompt.request)?.phase)
        assertFalse(gate.cancel())
    }

    @Test
    fun `handoff is immutable across restart and cancellation preserves its prepared target`() = runTest {
        val stores = ChecklistTestStores()
        val journal = StudioHelperAttempts(stores)
        val handoff = HelperHandoff(RequestInitiator(session, RequestId("source")), "harness", "private ancestry")
        val owned = prompt.copy(handoff = handoff)
        assertTrue(journal.prepare(helper, owned).isNew)
        assertFailsWith<IllegalStateException> { journal.begin(helper, prompt.request) }
        val bound = assertNotNull(journal.bindSession(helper, prompt.request, session))
        assertEquals(handoff, bound.handoff)
        assertNull(bound.session)
        assertNull(bound.turn)
        assertEquals(bound, journal.bindSession(helper, prompt.request, session))
        assertFailsWith<IllegalStateException> {
            journal.bindSession(helper, prompt.request, session.copy(nativeId = "different"))
        }
        val restarted = StudioHelperAttempts(stores)
        assertFalse(restarted.prepare(helper, owned).isNew)
        assertFailsWith<IllegalStateException> { restarted.prepare(helper, prompt) }
        assertFailsWith<IllegalStateException> {
            restarted.prepare(helper, owned.copy(handoff = handoff.copy(ownerContext = "changed")))
        }
        val cancelled = restarted.cancelBeforeSubmission(helper, prompt.request)
        assertEquals(handoff, cancelled.handoff)
        assertEquals(session, cancelled.preparedSession)
        assertEquals(StudioHelperPhase.NotSubmitted, cancelled.phase)
        assertNull(restarted.bindSession(helper, prompt.request, session))
        assertFalse(restarted.begin(helper, prompt.request))
    }

    @Test
    fun `prepared reference fences native evidence and distinct recovery can bind another session`() = runTest {
        val stores = ChecklistTestStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        journal.bindSession(helper, prompt.request, session)
        assertTrue(journal.begin(helper, prompt.request))
        assertFailsWith<IllegalStateException> {
            journal.accepted(helper, prompt.request, session.copy(nativeId = "wrong"), terminal.turn)
        }
        journal.terminal(helper, prompt.request, terminal)
        val recovery = prompt.copy(
            request = RequestId("recovery"),
            isRecovery = true,
            handoff = HelperHandoff(RequestInitiator(session, prompt.request)),
        )
        assertTrue(journal.prepare(helper, recovery).isNew)
        val restarted = StudioHelperAttempts(stores)
        val nextSession = session.copy(nativeId = "recovered")
        assertEquals(nextSession, restarted.bindSession(helper, recovery.request, nextSession)?.preparedSession)
        assertEquals(recovery.handoff, restarted.receipt(helper, recovery.request)?.handoff)
        assertTrue(restarted.begin(helper, recovery.request))
    }

    @Test
    fun `binding storage failure and lost acknowledgement never authorize an unbound target`() = runTest {
        val stores = AttemptStores()
        val journal = StudioHelperAttempts(stores)
        val owned = prompt.copy(handoff = HelperHandoff(ownerFeature = "harness", ownerContext = "context"))
        journal.prepare(helper, owned)
        stores.beforeWrite = { error("disk") }
        assertFailsWith<IllegalStateException> { journal.bindSession(helper, prompt.request, session) }
        assertNull(journal.receipt(helper, prompt.request)?.preparedSession)
        stores.beforeWrite = {}
        assertFailsWith<IllegalStateException> { journal.begin(helper, prompt.request) }
        stores.afterWrite = { throw CancellationException("lost ACK") }
        assertFailsWith<CancellationException> { journal.bindSession(helper, prompt.request, session) }
        stores.afterWrite = {}
        val restarted = StudioHelperAttempts(stores)
        assertEquals(session, restarted.bindSession(helper, prompt.request, session)?.preparedSession)
        assertFalse(restarted.prepare(helper, owned).isNew)
        assertFailsWith<IllegalStateException> {
            restarted.bindSession(helper, prompt.request, session.copy(nativeId = "other"))
        }
    }

    @Test
    fun `native handoff receipt without prepared identity fails closed on decode`() = runTest {
        val stores = ChecklistTestStores()
        val journal = StudioHelperAttempts(stores)
        val owned = prompt.copy(handoff = HelperHandoff(ownerFeature = "harness", ownerContext = "private ancestry"))
        journal.prepare(helper, owned)
        journal.bindSession(helper, prompt.request, session)
        journal.begin(helper, prompt.request)
        val values = stores.stores.getValue(HelperAttemptsSpec).values
        values.value = values.value.mapValues { (_, raw) ->
            val entries = Json.parseToJsonElement(raw as String).jsonObject
            JsonObject(
                entries.mapValues { (_, receipt) -> JsonObject(receipt.jsonObject - "preparedSession") },
            ).toString()
        }
        val failure = assertFailsWith<IllegalStateException> { journal.receipt(helper, prompt.request) }
        assertFalse(failure.message.orEmpty().contains("private"))
        assertNull(failure.cause)
    }

    @Test
    fun `legacy receipt defaults do not fabricate handoff or prepared identity`() {
        val old = Json.decodeFromString<StudioHelperReceipt>(
            """{"request":{"value":"R"},"phase":"Preparing","fingerprint":"legacy"}""",
        )
        assertNull(old.handoff)
        assertNull(old.preparedSession)
        assertEquals(StudioHelperPhase.Preparing, old.phase)
    }

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
