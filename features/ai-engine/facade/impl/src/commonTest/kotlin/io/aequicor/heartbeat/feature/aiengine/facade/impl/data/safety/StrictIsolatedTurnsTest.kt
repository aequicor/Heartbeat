package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptConstraints
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.TestEngine
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.managedKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class StrictIsolatedTurnsTest {
    private val prompt = IsolatedPrompt(
        EngineTarget(TestEngine, EngineBindingId("b1"), ModelId("m1")),
        "READY",
        timeout = 5.seconds,
        constraints = IsolatedPromptConstraints(managedKey().withoutLabel(), 32),
    )

    @Test
    fun `one dispatch releases capacity after adapter cleanup`() = runTest {
        val slots = IsolatedTurnSlots(1)
        var calls = 0
        val runner = StrictIsolatedTurns(backgroundScope, slots) { _, _ ->
            calls++
            assertEquals(1, slots.load.value.running)
            IsolatedAnswer.Answered("READY")
        }
        assertEquals(IsolatedAnswer.Answered("READY"), runner.run(prompt))
        runCurrent()
        assertEquals(1, calls)
        assertEquals(0, slots.load.value.running)
    }

    @Test
    fun `deadline returns without joining cleanup and slot stays owned until cleanup ends`() = runTest {
        val slots = IsolatedTurnSlots(1)
        val cleanup = CompletableDeferred<Unit>()
        var finished = false
        val runner = StrictIsolatedTurns(backgroundScope, slots) { _, _ ->
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
                finished = true
            }
        }
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout), runner.run(prompt))
        runCurrent()
        assertFalse(finished)
        assertEquals(1, slots.load.value.running)
        cleanup.complete(Unit)
        runCurrent()
        assertTrue(finished)
        assertEquals(0, slots.load.value.running)
    }

    @Test
    fun `queue waiting is included in deadline and never dispatches after timeout`() = runTest {
        val slots = IsolatedTurnSlots(1)
        assertTrue(slots.acquire())
        var called = false
        val runner = StrictIsolatedTurns(backgroundScope, slots) { _, _ ->
            called = true
            IsolatedAnswer.Answered("late")
        }
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout), runner.run(prompt))
        slots.releaseRunning()
        runCurrent()
        assertFalse(called)
        assertEquals(0, slots.load.value.running)
        assertEquals(0, slots.load.value.queued)
    }

    @Test
    fun `worker refuses dispatch after monotonic deadline even before caller timeout is processed`() = runTest {
        val clock = TestTimeSource()
        val slots = IsolatedTurnSlots(1)
        assertTrue(slots.acquire())
        var called = false
        val runner = StrictIsolatedTurns(backgroundScope, slots, clock) { _, _ ->
            called = true
            IsolatedAnswer.Answered("late")
        }
        val call = async { runner.run(prompt) }
        runCurrent()
        clock += 6.seconds
        slots.releaseRunning()
        runCurrent()
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout), call.await())
        assertFalse(called)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(0, slots.load.value.running)
    }

    @Test
    fun `caller cancellation cancels owned request without waiting for cleanup`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val slots = IsolatedTurnSlots(1)
        val runner = StrictIsolatedTurns(backgroundScope, slots) { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        val call = async { runner.run(prompt) }
        started.await()
        call.cancel()
        runCurrent()
        assertTrue(call.isCompleted)
        assertEquals(1, slots.load.value.running)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(0, slots.load.value.running)
    }

    @Test
    fun `profile cancellation returns closing even when cleanup is pending`() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val slots = IsolatedTurnSlots(1)
        val runner = StrictIsolatedTurns(owner, slots) { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        val call = async { runner.run(prompt) }
        started.await()
        owner.cancel()
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Closing), call.await())
        assertEquals(1, slots.load.value.running)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(0, slots.load.value.running)
    }

    @Test
    fun `adapter child cleanup retains capacity even after its answer function returns`() = runTest {
        val slots = IsolatedTurnSlots(1)
        val cleanup = CompletableDeferred<Unit>()
        val runner = StrictIsolatedTurns(backgroundScope, slots) { _, _ ->
            CoroutineScope(kotlin.coroutines.coroutineContext).launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
            IsolatedAnswer.Answered("not yet settled")
        }
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout), runner.run(prompt))
        runCurrent()
        assertEquals(1, slots.load.value.running)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(0, slots.load.value.running)
    }

    @Test
    fun `provider failure frees capacity and does not retry`() = runTest {
        val slots = IsolatedTurnSlots(1)
        var attempts = 0
        val runner = StrictIsolatedTurns(backgroundScope, slots) { _, _ ->
            attempts++
            error("private provider message")
        }
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Failed), runner.run(prompt))
        assertEquals(1, attempts)
        assertEquals(0, slots.load.value.running)
    }
}
