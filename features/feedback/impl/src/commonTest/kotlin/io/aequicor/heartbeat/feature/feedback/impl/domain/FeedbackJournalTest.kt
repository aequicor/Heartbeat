package io.aequicor.heartbeat.feature.feedback.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.feedback.api.FeedbackChange
import io.aequicor.heartbeat.feature.feedback.api.FeedbackIntent
import io.aequicor.heartbeat.feature.feedback.api.FeedbackMachineSpec
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutcome
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutput
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord
import io.aequicor.heartbeat.feature.feedback.api.FeedbackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackJournalTest {
    private val pending = FeedbackRecord(
        id = "operation",
        source = "chat",
        revision = 0,
        createdAt = Instant.parse("2026-09-30T10:00:00Z"),
        change = FeedbackChange.Trust(TrustLevel.Ask, TrustLevel.Full),
        outcome = FeedbackOutcome.Pending,
    )
    private val applied = pending.copy(
        revision = 1,
        outcome = FeedbackOutcome.Applied(SessionConfiguration(ModelId("model"), trust = TrustLevel.Full)),
    )

    @Test
    fun `unchanged restored history causes no write`() = runTest {
        val storage = MemoryStorage(listOf(applied))
        val machine = ReportingMachine()
        backgroundScope.launch { FeedbackJournal(storage).run(machine) }
        runCurrent()
        assertEquals(FeedbackState.Ready(listOf(applied)), machine.state.value)
        assertEquals(0, storage.snapshots.size)
    }

    @Test
    fun `pending restored history is persisted as unknown without native work`() = runTest {
        val storage = MemoryStorage(listOf(pending))
        val machine = ReportingMachine()
        backgroundScope.launch { FeedbackJournal(storage).run(machine) }
        runCurrent()
        assertEquals(listOf(pending.copy(revision = 1, outcome = FeedbackOutcome.Unknown)), storage.records)
        assertEquals(1, storage.snapshots.size)
    }

    @Test
    fun `records published while loading are retained after restoration`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val earlier = applied.copy(id = "earlier")
        val storage = MemoryStorage(listOf(earlier)).apply { loadGate = gate }
        val machine = ReportingMachine()
        backgroundScope.launch { FeedbackJournal(storage).run(machine) }
        runCurrent()
        machine.send(FeedbackIntent.Public.Publish(pending))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(earlier, pending), storage.records)
    }

    @Test
    fun `failed initial reads cannot overwrite unknown durable history`() = runTest {
        val earlier = applied.copy(id = "earlier")
        val storage = MemoryStorage(listOf(earlier)).apply { loadFailures = 2 }
        val machine = ReportingMachine()
        val journal = backgroundScope.launch { FeedbackJournal(storage).run(machine) }
        runCurrent()
        assertEquals(listOf(earlier), storage.records)
        assertEquals(0, storage.snapshots.size)
        assertTrue(journal.isActive)

        machine.send(FeedbackIntent.Public.Publish(pending))
        runCurrent()
        assertEquals(listOf(earlier, pending), storage.records)
    }

    @Test
    fun `save failure preserves live result and retries on the next report`() = runTest {
        val storage = MemoryStorage(emptyList()).apply { saveFailures = 1 }
        val machine = ReportingMachine()
        val journal = backgroundScope.launch { FeedbackJournal(storage).run(machine) }
        runCurrent()
        machine.send(FeedbackIntent.Public.Publish(pending))
        runCurrent()
        assertEquals(FeedbackState.Ready(listOf(pending)), machine.state.value)
        assertTrue(FeedbackOutput.StorageFailed in machine.emitted)
        assertTrue(journal.isActive)

        machine.send(FeedbackIntent.Public.Publish(applied))
        runCurrent()
        assertEquals(listOf(applied), storage.records)
    }

    @Test
    fun `slow writes stay serial and cannot leave an older result on disk`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val storage = MemoryStorage(emptyList()).apply { saveGate = gate }
        val machine = ReportingMachine()
        backgroundScope.launch { FeedbackJournal(storage).run(machine) }
        runCurrent()
        machine.send(FeedbackIntent.Public.Publish(pending))
        runCurrent()
        machine.send(FeedbackIntent.Public.Publish(applied))
        runCurrent()
        assertEquals(1, storage.writeAttempts)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(applied), storage.records)
        assertEquals(listOf(listOf(pending), listOf(applied)), storage.snapshots)
    }

    @Test
    fun `cancellation while loading is propagated without a failure report`() = runTest {
        val storage = MemoryStorage(emptyList()).apply { isCancelled = true }
        val machine = ReportingMachine()
        assertFailsWith<CancellationException> { FeedbackJournal(storage).run(machine) }
        assertIs<FeedbackState.Loading>(machine.state.value)
        assertTrue(machine.emitted.isEmpty())
    }

    private class MemoryStorage(var records: List<FeedbackRecord>) : FeedbackStorage {
        var loadFailures = 0
        var saveFailures = 0
        var loadGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var writeAttempts = 0
        var isCancelled = false
        val snapshots = mutableListOf<List<FeedbackRecord>>()

        override suspend fun load(): List<FeedbackRecord> {
            if (isCancelled) throw CancellationException("load cancelled")
            loadGate?.await()
            if (loadFailures > 0) {
                loadFailures--
                error("load failed")
            }
            return records
        }

        override suspend fun save(records: List<FeedbackRecord>) {
            writeAttempts++
            saveGate?.await()
            if (saveFailures > 0) {
                saveFailures--
                error("save failed")
            }
            snapshots += records
            this.records = records
        }
    }

    private class ReportingMachine : Machine<FeedbackState, FeedbackIntent, FeedbackOutput> {
        override val name = "feedback"
        override val state = MutableStateFlow(FeedbackMachineSpec.initial)
        override val outputs = MutableSharedFlow<FeedbackOutput>()
        val emitted = mutableListOf<FeedbackOutput>()

        override suspend fun send(intent: FeedbackIntent): SendResult {
            val resolved = FeedbackMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
            state.value = resolved.to
            emitted += resolved.outputs
            resolved.outputs.forEach { outputs.emit(it) }
            return SendResult.Accepted
        }
    }
}
