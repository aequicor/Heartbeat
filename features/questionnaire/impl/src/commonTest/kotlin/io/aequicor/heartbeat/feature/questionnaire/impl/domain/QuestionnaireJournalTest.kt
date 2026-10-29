package io.aequicor.heartbeat.feature.questionnaire.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.LIVE_QUESTION_ID_PREFIX
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineSpec
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnairePublicationHistory
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QuestionnaireJournalTest {
    private val saved = Questionnaire(QuestionnaireId("s1/r1"), "s1", "Go?", Question.Confirm("Yes", "No"))

    @Test
    fun `saved questions are asked again and every queue change is saved`() = runTest {
        val storage = MemoryStorage(listOf(saved))
        val machine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertEquals(
            listOf<QuestionnaireIntent>(
                QuestionnaireIntent.Public.Restore(saved),
                QuestionnaireIntent.Public.FinishInitialRestore,
            ),
            machine.sent,
        )
        val restored = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertTrue(restored.publications.isInitialRestoreFinished)
        assertTrue(restored.publications.touched.isEmpty())
        // The restored queue equals the saved one: nothing is written.
        assertEquals(0, storage.saves)

        machine.state.value = QuestionnaireState.Idle
        runCurrent()
        assertEquals(emptyList(), storage.pending)
    }

    @Test
    fun `an unconfirmed seal never overwrites durable questions`() = runTest {
        val storage = MemoryStorage(listOf(saved))
        val machine = RecordingMachine().apply { result = SendResult.Ignored }
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertEquals(listOf(saved), storage.pending)
        assertEquals(0, storage.saves)

        val next = saved.copy(id = QuestionnaireId("s1/r2"))
        machine.state.value = QuestionnaireState.Asking(listOf(next))
        runCurrent()
        assertEquals(listOf(saved), storage.pending)
    }

    @Test
    fun `answers in flight are not saved`() = runTest {
        val storage = MemoryStorage(emptyList())
        val machine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        val other = saved.copy(id = QuestionnaireId("s1/r2"))
        machine.state.value = QuestionnaireState.Asking(listOf(saved, other), submitting = setOf(saved.id))
        runCurrent()
        assertEquals(listOf(other), storage.pending)
    }

    @Test
    fun `questions of live callers stay out of storage`() = runTest {
        val storage = MemoryStorage(emptyList())
        val machine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        val live = saved.copy(id = QuestionnaireId("${LIVE_QUESTION_ID_PREFIX}s1/plan/q"))

        machine.state.value = QuestionnaireState.Asking(listOf(saved, live))
        runCurrent()
        assertEquals(listOf(saved), storage.pending)
    }

    @Test
    fun `storage failures are logged and the journal keeps saving`() = runTest {
        val storage = MemoryStorage(emptyList())
        val machine = RecordingMachine()
        val journal = backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        storage.failures = 1

        machine.state.value = QuestionnaireState.Asking(listOf(saved))
        runCurrent()
        machine.state.value = QuestionnaireState.Idle
        runCurrent()
        machine.state.value = QuestionnaireState.Asking(listOf(saved))
        runCurrent()
        assertTrue(journal.isActive)
        assertEquals(listOf(saved), storage.pending)
    }

    @Test
    fun `journal load cannot resurrect a question withdrawn during loading`() = runTest {
        for (wasPublished in listOf(false, true)) {
            val loaded = CompletableDeferred<Unit>()
            val storage = MemoryStorage(listOf(saved)).apply { beforeLoad = { loaded.await() } }
            val machine = RecordingMachine()
            backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
            runCurrent()
            if (wasPublished) machine.send(QuestionnaireIntent.Public.Ask(saved.copy(title = "New publication")))
            machine.send(QuestionnaireIntent.Public.Withdraw(saved.id))
            loaded.complete(Unit)
            runCurrent()
            assertEquals(emptyList(), assertIs<QuestionnaireState.Asking>(machine.state.value).pending)
            assertEquals(emptyList(), storage.pending)
            assertEquals(1, storage.saves)
        }
    }

    @Test
    fun `journal preserves a publication asked while storage is loading`() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val storage = MemoryStorage(listOf(saved)).apply { beforeLoad = { loaded.await() } }
        val machine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        val fresh = saved.copy(title = "Current publication")
        val ask = QuestionnaireIntent.Public.Ask(fresh)
        machine.send(ask)
        loaded.complete(Unit)
        runCurrent()
        val current = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertEquals(listOf(fresh), current.pending)
        assertEquals(ask.generation, current.generations[saved.id])
        assertEquals(listOf(fresh), storage.pending)
    }

    @Test
    fun `publication between restore dispatch and resolution is checked atomically`() = runTest {
        val storage = MemoryStorage(listOf(saved))
        val machine = RecordingMachine()
        machine.beforeRestore = {
            machine.send(QuestionnaireIntent.Public.Ask(saved))
            machine.send(QuestionnaireIntent.Public.Withdraw(saved.id))
        }
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertEquals(emptyList(), assertIs<QuestionnaireState.Asking>(machine.state.value).pending)
        assertEquals(emptyList(), storage.pending)
    }

    @Test
    fun `journal restart on the same machine cannot reopen answered or withdrawn entries`() = runTest {
        val storage = MemoryStorage(listOf(saved))
        val machine = RecordingMachine()
        val first = backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        val generation = assertIs<QuestionnaireState.Asking>(machine.state.value).generations.getValue(saved.id)
        first.cancel()
        machine.send(QuestionnaireIntent.Public.Withdraw(saved.id))
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertEquals(emptyList(), assertIs<QuestionnaireState.Asking>(machine.state.value).pending)
        assertEquals(emptyList(), storage.pending)
        // A fresh profile machine has no touches and may restore the original durable format.
        val nextMachine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(MemoryStorage(listOf(saved))).run(nextMachine) }
        runCurrent()
        val next = assertIs<QuestionnaireState.Asking>(nextMachine.state.value)
        assertEquals(listOf(saved), next.pending)
        assertNotEquals(generation, next.generations[saved.id])
    }

    @Test
    fun `journal restores LIMIT plus two durable questions without truncating storage`() = runTest {
        val durable = (0 until QuestionnairePublicationHistory.LIMIT + 2).map {
            saved.copy(id = QuestionnaireId("durable-$it"))
        }
        val storage = MemoryStorage(durable)
        val machine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        val restored = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertEquals(durable, restored.pending)
        assertEquals(durable, storage.pending)
        assertEquals(0, storage.saves)
        assertEquals(QuestionnairePublicationHistory.LIMIT, restored.publications.latest.size)
        assertTrue(restored.publications.touched.isEmpty())
        assertTrue(restored.publications.isInitialRestoreFinished)
        // Early restored generations remain guarded even after their history entry was evicted.
        val generation = restored.generations.getValue(durable.first().id)
        assertEquals(
            SendResult.Accepted,
            machine.send(
                QuestionnaireIntent.Public.Answer(
                    durable.first().id,
                    Answer.Confirmed(true),
                    expectedGeneration = generation,
                ),
            ),
        )
        runCurrent()
        assertEquals(durable.drop(1), storage.pending)
    }

    @Test
    fun `delayed journal keeps untouched durable records but cannot resurrect evicted withdrawals`() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val untouched = saved.copy(id = QuestionnaireId("untouched"))
        val storage = MemoryStorage(listOf(saved, untouched)).apply { beforeLoad = { loaded.await() } }
        val machine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        machine.send(QuestionnaireIntent.Public.Withdraw(saved.id))
        repeat(QuestionnairePublicationHistory.LIMIT + 1) {
            machine.send(QuestionnaireIntent.Public.Withdraw(QuestionnaireId("cancelled-$it")))
        }
        assertFalse(saved.id in assertIs<QuestionnaireState.Asking>(machine.state.value).publications.latest)
        loaded.complete(Unit)
        runCurrent()
        assertEquals(listOf(untouched), assertIs<QuestionnaireState.Asking>(machine.state.value).pending)
        assertEquals(listOf(untouched), storage.pending)
        assertEquals(1, storage.saves)
    }

    @Test
    fun `concurrent restores respect cancellation and live delivery at resolution after overflow`() = runTest {
        val cancelled = saved.copy(id = QuestionnaireId("cancelled"))
        val live = saved.copy(id = QuestionnaireId("${LIVE_QUESTION_ID_PREFIX}s1/live"))
        val durable = (0 until QuestionnairePublicationHistory.LIMIT + 2).map {
            saved.copy(id = QuestionnaireId("durable-$it"))
        }
        val machine = RecordingMachine()
        durable.forEach { machine.send(QuestionnaireIntent.Public.Restore(it)) }
        val beforeResolution = CompletableDeferred<Unit>()
        val resolve = CompletableDeferred<Unit>()
        machine.beforeRestore = {
            beforeResolution.complete(Unit)
            resolve.await()
        }
        val storage = MemoryStorage(listOf(saved, cancelled) + durable)
        val journal = backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        beforeResolution.await()
        // Restore has been dispatched but not resolved. Native/current content and live gates must win.
        val fresh = QuestionnaireIntent.Public.Ask(saved.copy(title = "Native replacement"))
        machine.send(fresh)
        machine.send(
            QuestionnaireIntent.Public.Answer(
                saved.id,
                Answer.Confirmed(true),
                expectedGeneration = fresh.generation,
            ),
        )
        machine.send(QuestionnaireIntent.Public.BeginDelivery(saved.id, fresh.generation))
        machine.send(QuestionnaireIntent.Public.Withdraw(cancelled.id))
        val liveAsk = QuestionnaireIntent.Public.Ask(live)
        machine.send(liveAsk)
        machine.send(
            QuestionnaireIntent.Public.Answer(
                live.id,
                Answer.Confirmed(true),
                expectedGeneration = liveAsk.generation,
            ),
        )
        machine.send(QuestionnaireIntent.Public.BeginDelivery(live.id, liveAsk.generation))
        resolve.complete(Unit)
        runCurrent()
        val current = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertEquals(fresh.generation, current.generations[saved.id])
        assertEquals(setOf(saved.id, live.id), current.preparing)
        assertFalse(current.pending.any { it.id == cancelled.id })
        assertEquals(durable, storage.pending)
        // A restarted journal must not reopen a submitting native question or an evicted cancellation.
        journal.cancel()
        val staleStorage = MemoryStorage(listOf(saved, cancelled) + durable)
        backgroundScope.launch { QuestionnaireJournal(staleStorage).run(machine) }
        runCurrent()
        assertEquals(current, machine.state.value)
        assertEquals(durable, staleStorage.pending)
        assertEquals(
            SendResult.Accepted,
            machine.send(QuestionnaireIntent.Public.AcceptDelivery(saved.id, fresh.generation)),
        )
        assertEquals(
            SendResult.Accepted,
            machine.send(QuestionnaireIntent.Public.AcceptDelivery(live.id, liveAsk.generation)),
        )
    }

    @Test
    fun `overlapping journal loads cannot restore a cancellation or replace another journal publication`() = runTest {
        val firstLoad = CompletableDeferred<Unit>()
        val secondLoad = CompletableDeferred<Unit>()
        val other = saved.copy(id = QuestionnaireId("other"))
        var loads = 0
        val storage = MemoryStorage(listOf(saved, other)).apply {
            beforeLoad = { if (loads++ == 0) firstLoad.await() else secondLoad.await() }
        }
        val machine = RecordingMachine()
        repeat(QuestionnairePublicationHistory.LIMIT + 2) {
            machine.send(QuestionnaireIntent.Public.Withdraw(QuestionnaireId("old-$it")))
        }
        repeat(2) { backgroundScope.launch { QuestionnaireJournal(storage).run(machine) } }
        runCurrent()
        assertEquals(2, loads)
        firstLoad.complete(Unit)
        runCurrent()
        val generation = assertIs<QuestionnaireState.Asking>(machine.state.value).generations.getValue(other.id)
        machine.send(QuestionnaireIntent.Public.Withdraw(saved.id))
        runCurrent()
        secondLoad.complete(Unit)
        runCurrent()
        val current = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertEquals(listOf(other), current.pending)
        assertEquals(generation, current.generations[other.id])
        assertEquals(listOf(other), storage.pending)
    }

    @Test
    fun `stopped machine during partial restore leaves durable records intact`() = runTest {
        val other = saved.copy(id = QuestionnaireId("other"))
        val storage = MemoryStorage(listOf(saved, other))
        val machine = RecordingMachine()
        machine.beforeRestore = {
            if (machine.sent.size == 2) machine.result = SendResult.NotRunning
        }
        val journal = backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertTrue(journal.isCompleted)
        assertEquals(listOf(saved), assertIs<QuestionnaireState.Asking>(machine.state.value).pending)
        assertEquals(listOf(saved, other), storage.pending)
        assertEquals(0, storage.saves)
    }

    @Test
    fun `failed load keeps exclusions and durable queue until a successful retry`() = runTest {
        val storage = MemoryStorage(listOf(saved)).apply { failures = 1 }
        val machine = RecordingMachine()
        machine.send(QuestionnaireIntent.Public.Withdraw(saved.id))
        val failed = backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertTrue(failed.isCompleted)
        val current = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertFalse(current.publications.isInitialRestoreFinished)
        assertEquals(setOf(saved.id), current.publications.touched)
        assertFalse(machine.sent.contains(QuestionnaireIntent.Public.FinishInitialRestore))
        assertEquals(0, storage.saves)
        assertEquals(listOf(saved), storage.pending)
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertTrue(assertIs<QuestionnaireState.Asking>(machine.state.value).publications.isInitialRestoreFinished)
        assertEquals(emptyList(), storage.pending)
    }

    @Test
    fun `empty durable queue still seals restoration`() = runTest {
        val storage = MemoryStorage(emptyList())
        val machine = RecordingMachine()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        val current = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertTrue(current.publications.isInitialRestoreFinished)
        assertTrue(current.publications.touched.isEmpty())
        assertEquals(SendResult.Ignored, machine.send(QuestionnaireIntent.Public.Restore(saved)))
        assertEquals(0, storage.saves)
    }

    @Test
    fun `NotRunning at seal does not save or release exclusions`() = runTest {
        val storage = MemoryStorage(listOf(saved))
        val machine = RecordingMachine().apply { beforeSeal = { result = SendResult.NotRunning } }
        val journal = backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertTrue(journal.isCompleted)
        val current = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertFalse(current.publications.isInitialRestoreFinished)
        assertEquals(setOf(saved.id), current.publications.touched)
        assertEquals(0, storage.saves)
        assertEquals(listOf(saved), storage.pending)
    }

    @Test
    fun `cancellation during load or before seal never saves and leaves restoration open`() = runTest {
        for (duringLoad in listOf(true, false)) {
            val waiting = CompletableDeferred<Unit>()
            val storage = MemoryStorage(listOf(saved))
            val machine = RecordingMachine()
            if (duringLoad) storage.beforeLoad = { waiting.await() } else machine.beforeSeal = { waiting.await() }
            val journal = backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
            runCurrent()
            journal.cancel()
            runCurrent()
            assertTrue(journal.isCancelled)
            val current = machine.state.value as? QuestionnaireState.Asking
            assertFalse(current?.publications?.isInitialRestoreFinished == true)
            assertEquals(0, storage.saves)
            assertEquals(listOf(saved), storage.pending)
        }
    }

    @Test
    fun `cancellation during partial restore retains exclusions for a complete retry`() = runTest {
        val other = saved.copy(id = QuestionnaireId("other"))
        val storage = MemoryStorage(listOf(saved, other))
        val machine = RecordingMachine()
        val waiting = CompletableDeferred<Unit>()
        machine.beforeRestore = { if (machine.sent.size == 2) waiting.await() }
        val journal = backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        journal.cancel()
        runCurrent()
        val partial = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertFalse(partial.publications.isInitialRestoreFinished)
        assertEquals(setOf(saved.id), partial.publications.touched)
        assertEquals(listOf(saved, other), storage.pending)
        assertEquals(0, storage.saves)
        machine.beforeRestore = {}
        machine.send(QuestionnaireIntent.Public.Withdraw(saved.id))
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        val restored = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertTrue(restored.publications.isInitialRestoreFinished)
        assertTrue(restored.publications.touched.isEmpty())
        assertEquals(listOf(other), restored.pending)
        assertEquals(listOf(other), storage.pending)
    }

    @Test
    fun `cancellation after accepted seal permits safe restart on the same machine`() = runTest {
        val storage = MemoryStorage(listOf(saved))
        val machine = RecordingMachine().apply {
            afterSeal = { throw CancellationException("cancelled after transition") }
        }
        val first = backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertTrue(first.isCancelled)
        assertTrue(assertIs<QuestionnaireState.Asking>(machine.state.value).publications.isInitialRestoreFinished)
        assertEquals(0, storage.saves)
        machine.send(QuestionnaireIntent.Public.Withdraw(saved.id))
        machine.afterSeal = {}
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        assertEquals(emptyList(), storage.pending)
        assertEquals(emptyList(), assertIs<QuestionnaireState.Asking>(machine.state.value).pending)
    }

    @Test
    fun `overlapping dispatched restore cannot reopen an evicted withdrawal after seal`() = runTest {
        val storage = MemoryStorage(listOf(saved))
        val machine = RecordingMachine()
        val blocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var restores = 0
        machine.beforeRestore = {
            if (restores++ == 0) {
                blocked.complete(Unit)
                release.await()
            }
        }
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        blocked.await()
        backgroundScope.launch { QuestionnaireJournal(storage).run(machine) }
        runCurrent()
        machine.send(QuestionnaireIntent.Public.Withdraw(saved.id))
        repeat(QuestionnairePublicationHistory.LIMIT + 1) {
            machine.send(QuestionnaireIntent.Public.Withdraw(QuestionnaireId("churn-$it")))
        }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        val current = assertIs<QuestionnaireState.Asking>(machine.state.value)
        assertTrue(current.publications.isInitialRestoreFinished)
        assertTrue(current.publications.touched.isEmpty())
        assertFalse(saved.id in current.publications.latest)
        assertTrue(current.pending.isEmpty())
        assertEquals(emptyList(), storage.pending)
    }

    private class MemoryStorage(var pending: List<Questionnaire>) : QuestionnaireStorage {
        var failures = 0
        var saves = 0
        var beforeLoad: suspend () -> Unit = {}

        override suspend fun load(): List<Questionnaire> {
            val snapshot = pending
            beforeLoad()
            if (failures > 0) {
                failures--
                error("load failed")
            }
            return snapshot
        }

        override suspend fun save(pending: List<Questionnaire>) {
            if (failures > 0) {
                failures--
                error("save failed")
            }
            saves++
            this.pending = pending
        }
    }

    private class RecordingMachine : Machine<QuestionnaireState, QuestionnaireIntent, QuestionnaireOutput> {
        override val name = "questionnaire"
        override val state = MutableStateFlow<QuestionnaireState>(QuestionnaireState.Idle)
        override val outputs = MutableSharedFlow<QuestionnaireOutput>()
        val sent = mutableListOf<QuestionnaireIntent>()
        var result: SendResult = SendResult.Accepted
        var beforeRestore: suspend () -> Unit = {}
        var beforeSeal: suspend () -> Unit = {}
        var afterSeal: suspend () -> Unit = {}

        /** Resolves the real pure spec in send order; a hook models work queued before restore processing. */
        override suspend fun send(intent: QuestionnaireIntent): SendResult {
            sent += intent
            if (intent is QuestionnaireIntent.Public.Restore) beforeRestore()
            if (intent == QuestionnaireIntent.Public.FinishInitialRestore) beforeSeal()
            if (result != SendResult.Accepted) return result
            val transition = QuestionnaireMachineSpec.resolve(state.value, intent)
            return if (transition == null) {
                SendResult.Ignored
            } else {
                state.value = transition.to
                if (intent == QuestionnaireIntent.Public.FinishInitialRestore) afterSeal()
                SendResult.Accepted
            }
        }
    }
}
