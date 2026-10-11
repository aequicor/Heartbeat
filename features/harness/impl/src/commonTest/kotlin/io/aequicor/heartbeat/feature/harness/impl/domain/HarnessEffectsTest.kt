package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineSpec
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HarnessEffectsTest {
    @Test
    fun `uncertain write retries the exact receipt without false failure feedback`() = runTest {
        val storage = MemoryLibrary().apply {
            beforeSave = { if (saved.size == 1) throw HarnessStorageUncertain() }
        }
        val feedback = RecordingFeedback()
        backgroundScope.launch {
            HarnessEffects(
                storage,
                RecordingRuntime(),
            ).handle(HarnessEffect.Save(harness, receipt), feedback)
        }
        runCurrent()
        assertTrue(feedback.sent.isEmpty())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(receipt, receipt), storage.saved)
        assertEquals(listOf<HarnessIntent>(HarnessIntent.Internal.Saved(receipt)), feedback.sent)
    }

    @Test
    fun `confirmed rollback maps to the exact failure and cancellation does not create a receipt`() = runTest {
        val storage = MemoryLibrary().apply { beforeSave = { throw HarnessStorageFailure() } }
        val effects = HarnessEffects(storage, RecordingRuntime())
        val feedback = RecordingFeedback()
        val effect = HarnessEffect.Save(harness, receipt)
        val error = assertFailsWith<HarnessStorageFailure> { effects.handle(effect, feedback) }
        assertEquals(HarnessIntent.Internal.SaveFailed(receipt), HarnessMachineSpec.onEffectFailure(effect, error))
        storage.beforeSave = { throw HarnessStorageUncertain() }
        val pending = backgroundScope.launch { effects.handle(effect, feedback) }
        runCurrent()
        pending.cancelAndJoin()
        assertTrue(feedback.sent.isEmpty())
    }

    @Test
    fun `unconfirmed runtime cleanup keeps storage and receipt pending until proof`() = runTest {
        val storage = MemoryLibrary()
        val runtime = RecordingRuntime().apply { canRemove = false }
        val feedback = RecordingFeedback()
        backgroundScope.launch {
            HarnessEffects(
                storage,
                runtime,
            ).handle(HarnessEffect.Remove(harness, receipt), feedback)
        }
        runCurrent()
        assertEquals(0, storage.removed)
        assertTrue(feedback.sent.isEmpty())
        runtime.canRemove = true
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, storage.removed)
        assertEquals(listOf<HarnessIntent>(HarnessIntent.Internal.Removed(receipt)), feedback.sent)
    }

    @Test
    fun `code that never drains commits the deletion without reactivating it`() = runTest {
        val storage = MemoryLibrary()
        val runtime = RecordingRuntime().apply {
            canRemove = false
            isRemovedCleanupDone = false
        }
        val feedback = RecordingFeedback()
        val work = backgroundScope.launch {
            HarnessEffects(storage, runtime).handle(HarnessEffect.Remove(harness, receipt), feedback)
        }
        advanceTimeBy(REMOVAL_DRAIN_LIMIT - 1.seconds)
        runCurrent()
        assertTrue(work.isActive)
        assertTrue(feedback.sent.isEmpty())
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(1, storage.removed)
        assertEquals(listOf<HarnessIntent>(HarnessIntent.Internal.Removed(receipt)), feedback.sent)
        assertTrue(runtime.activated.isEmpty())
        // The committed deletion keeps finishing its cleanup behind the original fence.
        assertTrue(work.isActive)
        assertEquals(1, runtime.cleanedUp.size)
        runtime.isRemovedCleanupDone = true
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(work.isCompleted)
        assertEquals(2, runtime.cleanedUp.size)
        assertTrue(runtime.activated.isEmpty())
    }

    @Test
    fun `expired deletion with an uncertain write retries the exact removal`() = runTest {
        val storage = MemoryLibrary().apply { beforeRemove = { if (removed == 1) throw HarnessStorageUncertain() } }
        val runtime = RecordingRuntime().apply { canRemove = false }
        val feedback = RecordingFeedback()
        backgroundScope.launch {
            HarnessEffects(storage, runtime).handle(HarnessEffect.Remove(harness, receipt), feedback)
        }
        advanceTimeBy(REMOVAL_DRAIN_LIMIT + 1.seconds)
        runCurrent()
        assertEquals(1, storage.removed)
        assertTrue(feedback.sent.isEmpty())
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(2, storage.removed)
        assertEquals(listOf<HarnessIntent>(HarnessIntent.Internal.Removed(receipt)), feedback.sent)
        assertTrue(runtime.activated.isEmpty())
    }

    @Test
    fun `long unconfirmed cleanup backs off instead of retrying every five seconds`() = runTest {
        var attempts = 0
        val runtime = object : HarnessRuntimeControl by RecordingRuntime() {
            override suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean {
                attempts++
                return false
            }
        }
        val effect = HarnessEffect.Deactivate(emptyList(), true, setOf(harness.id), 17)
        backgroundScope.launch { HarnessEffects(MemoryLibrary(), runtime).handle(effect, RecordingFeedback()) }
        advanceTimeBy(10.minutes)
        runCurrent()
        // 5 + 10 + 20 + 40 seconds, then one attempt per minute: far fewer than 120 fixed five-second retries.
        assertTrue(attempts in 10..15, "attempts=$attempts")
    }

    @Test
    fun `deactivation forwards harness fences even when no code items remain`() = runTest {
        val runtime = RecordingRuntime()
        val effect = HarnessEffect.Deactivate(emptyList(), true, setOf(harness.id), 17)
        HarnessEffects(MemoryLibrary(), runtime).handle(effect, RecordingFeedback())
        assertEquals(listOf(effect), runtime.deactivated)
    }

    @Test
    fun `uncertain deactivation journal read retries the same fence until cleanup completes`() = runTest {
        val seen = mutableListOf<HarnessEffect.Deactivate>()
        val runtime = object : HarnessRuntimeControl by RecordingRuntime() {
            override suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean {
                seen += effect
                if (seen.size == 1) throw HarnessStorageUncertain()
                return seen.size >= 3
            }
        }
        val effect = HarnessEffect.Deactivate(emptyList(), true, setOf(harness.id), 17)
        val work = backgroundScope.launch {
            HarnessEffects(MemoryLibrary(), runtime).handle(effect, RecordingFeedback())
        }
        runCurrent()
        assertTrue(work.isActive)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(work.isActive)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(work.isCompleted)
        assertEquals(listOf(effect, effect, effect), seen)
    }

    @Test
    fun `uncertain deletion rechecks exact runtime admission before touching storage again`() = runTest {
        val storage = MemoryLibrary().apply { beforeRemove = { throw HarnessStorageUncertain() } }
        val runtime = RecordingRuntime()
        val feedback = RecordingFeedback()
        val work = backgroundScope.launch {
            HarnessEffects(storage, runtime).handle(HarnessEffect.Remove(harness, receipt), feedback)
        }
        runCurrent()
        assertEquals(1, storage.removed)
        runtime.isRemovalCurrent = false
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(work.isCompleted)
        assertEquals(1, storage.removed)
        assertTrue(feedback.sent.isEmpty())
    }

    @Test
    fun `already obsolete removal never writes storage or emits a receipt`() = runTest {
        val storage = MemoryLibrary()
        val runtime = RecordingRuntime().apply { isRemovalCurrent = false }
        val feedback = RecordingFeedback()
        HarnessEffects(storage, runtime).handle(HarnessEffect.Remove(harness, receipt), feedback)
        assertEquals(0, storage.removed)
        assertTrue(feedback.sent.isEmpty())
    }
}
