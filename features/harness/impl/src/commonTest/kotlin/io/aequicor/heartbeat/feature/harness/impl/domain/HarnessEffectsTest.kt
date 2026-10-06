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
    fun `deactivation forwards harness fences even when no code items remain`() = runTest {
        val runtime = RecordingRuntime()
        val effect = HarnessEffect.Deactivate(emptyList(), true, setOf(harness.id), 17)
        HarnessEffects(MemoryLibrary(), runtime).handle(effect, RecordingFeedback())
        assertEquals(listOf(effect), runtime.deactivated)
    }
}
