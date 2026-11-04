package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckIncomplete
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckOutcome
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReport
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCheckBatchTest {
    @Test
    fun `the saved phase callback consumes the same deadline and cannot start late probes`() = runTest {
        var callbacks = 0
        val executor = ModelCheckBatch(
            backgroundScope,
            ModelCheckProbes(IsolatedPrompts { error("Phase callback exhausted the budget") }),
            testScheduler.timeSource,
        )
        val result = assertIs<ModelCheckBatchResult.TimedOut<String>>(
            executor.execute(
                request().copy(onSaved = {
                    callbacks++
                    delay(10.seconds)
                }),
            ),
        )
        assertEquals(1, callbacks)
        assertEquals("saved", result.saved)
        assertTrue(result.reports.isEmpty())
        assertEquals(5000, testScheduler.currentTime)
    }

    @Test
    fun `one shared deadline starts before saving and is not reset for the next model`() = runTest {
        val requests = mutableListOf<Pair<String, Long>>()
        val executor = ModelCheckBatch(
            backgroundScope,
            ModelCheckProbes(
                IsolatedPrompts {
                    requests += it.target.model.value to it.timeout.inWholeMilliseconds
                    delay(1.seconds)
                    IsolatedAnswer.Answered(if (requests.size % 2 == 1) "READY" else "19|cedar|ABSENT")
                },
            ),
            testScheduler.timeSource,
        )
        val result = executor.execute(
            request(save = {
                delay(1.seconds)
                "saved"
            }, revisions = { listOf(checkReport("first").revision, checkReport("second").revision) }),
        )
        assertIs<ModelCheckBatchResult.TimedOut<String>>(result)
        assertEquals("saved", result.saved)
        assertEquals(2, result.reports.size)
        assertFalse(result.reports.first().hasWarning)
        assertEquals(ModelCheckOutcome.Passed, result.reports.last().response.outcome)
        assertEquals(
            ModelCheckOutcome.Incomplete(ModelCheckIncomplete.Timeout),
            result.reports.last().accuracy.outcome,
        )
        assertEquals(listOf("first" to 4000L, "first" to 3000L, "second" to 2000L, "second" to 1000L), requests)
        assertEquals(5000, testScheduler.currentTime)
    }

    @Test
    fun `accepted save may finish after the deadline without delaying caller or starting probes`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved = false
        var discovered = false
        val executor = ModelCheckBatch(
            backgroundScope,
            ModelCheckProbes(IsolatedPrompts { error("Late save must not start model IO") }),
            testScheduler.timeSource,
        )
        val result = async {
            executor.execute(
                request(save = {
                    entered.complete(Unit)
                    release.await()
                    saved = true
                    "saved"
                }, revisions = {
                    discovered = true
                    listOf(checkReport().revision)
                }),
            )
        }
        entered.await()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(result.isCompleted)
        assertNull(assertIs<ModelCheckBatchResult.TimedOut<String>>(result.await()).saved)
        assertFalse(saved)
        release.complete(Unit)
        runCurrent()
        assertTrue(saved)
        assertFalse(discovered)
    }

    @Test
    fun `worker keeps its resource until an accepted save settles after timeout`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        val executor = ModelCheckBatch(
            backgroundScope,
            ModelCheckProbes(IsolatedPrompts { error("Late save must not probe") }),
            testScheduler.timeSource,
        )
        val result = async {
            executor.execute(
                request(save = {
                    entered.complete(Unit)
                    release.await()
                    assertFalse(closed)
                    "saved"
                }).copy(onFinished = { closed = true }),
            )
        }
        entered.await()
        advanceTimeBy(5000)
        runCurrent()
        assertIs<ModelCheckBatchResult.TimedOut<String>>(result.await())
        assertFalse(closed)
        release.complete(Unit)
        runCurrent()
        assertTrue(closed)
    }

    @Test
    fun `pending adapter cleanup cannot extend the UI deadline`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        var finished = false
        val executor = ModelCheckBatch(
            backgroundScope,
            ModelCheckProbes(
                IsolatedPrompts {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { cleanup.await() }
                        finished = true
                    }
                },
            ),
            testScheduler.timeSource,
        )
        val result = async { executor.execute(request()) }
        entered.await()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(result.isCompleted)
        val timeout = assertIs<ModelCheckBatchResult.TimedOut<String>>(result.await())
        assertEquals("saved", timeout.saved)
        val report = timeout.reports.single()
        assertEquals(ModelCheckOutcome.Incomplete(ModelCheckIncomplete.Timeout), report.response.outcome)
        assertEquals(ModelCheckOutcome.Incomplete(ModelCheckIncomplete.Timeout), report.accuracy.outcome)
        assertFalse(finished)
        cleanup.complete(Unit)
        runCurrent()
        assertTrue(finished)
    }

    @Test
    fun `caller cancellation never joins an accepted save and its late completion does not check models`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var isSaved = false
        val executor = ModelCheckBatch(
            backgroundScope,
            ModelCheckProbes(IsolatedPrompts { error("Cancelled batch must not check models") }),
            testScheduler.timeSource,
        )
        val result = async {
            executor.execute(
                request(save = {
                    entered.complete(Unit)
                    release.await()
                    isSaved = true
                    "saved"
                }),
            )
        }
        entered.await()
        result.cancelAndJoin()
        assertFalse(isSaved)
        release.complete(Unit)
        runCurrent()
        assertTrue(isSaved)
        assertTrue(result.isCancelled)
    }

    @Test
    fun `a blocked revision read also consumes the budget and returns without joining cleanup`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executor = ModelCheckBatch(
            backgroundScope,
            ModelCheckProbes(IsolatedPrompts { error("Late revision read must not send a request") }),
            testScheduler.timeSource,
        )
        val result = async {
            executor.execute(
                request(revisions = {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    listOf(checkReport().revision)
                }),
            )
        }
        entered.await()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(result.isCompleted)
        assertIs<ModelCheckBatchResult.TimedOut<String>>(result.await())
        release.complete(Unit)
        runCurrent()
    }

    @Test
    fun `completion retains saved choices and independent reports`() = runTest {
        var count = 0
        var saves = 0
        val executor = ModelCheckBatch(
            backgroundScope,
            ModelCheckProbes(
                IsolatedPrompts {
                    IsolatedAnswer.Answered(if (++count % 2 == 1) "READY" else "19|cedar|ABSENT")
                },
            ),
            testScheduler.timeSource,
        )
        val result = assertIs<ModelCheckBatchResult.Completed<String>>(
            executor.execute(
                request(save = {
                    saves++
                    "saved"
                }, revisions = {
                    listOf(checkReport("first").revision, checkReport("second").revision, checkReport("first").revision)
                }),
            ),
        )
        assertEquals("saved", result.saved)
        assertEquals(1, saves)
        assertEquals(4, count)
        assertEquals(2, result.reports.size)
        assertTrue(result.reports.none(ModelCheckReport::hasWarning))
    }

    @Test
    fun `an invalidated model produces no publishable report`() = runTest {
        val executor = ModelCheckBatch(
            backgroundScope,
            ModelCheckProbes(IsolatedPrompts { error("Obsolete run must not send requests") }),
            testScheduler.timeSource,
        )
        val result = assertIs<ModelCheckBatchResult.Completed<String>>(
            executor.execute(request(isCurrent = { false })),
        )
        assertTrue(result.reports.isEmpty())
    }

    @Test
    fun `a failure after saving preserves reconciliation information without exposing exception text`() = runTest {
        val executor = ModelCheckBatch(
            backgroundScope,
            ModelCheckProbes(IsolatedPrompts { error("No probes on revision failure") }),
            testScheduler.timeSource,
        )
        val result = assertIs<ModelCheckBatchResult.Failed<String>>(
            executor.execute(
                request(revisions = {
                    error("secret-exception-canary")
                }),
            ),
        )
        assertEquals("saved", result.saved)
        assertFalse(result.toString().contains("canary"))
    }

    @Test
    fun `a cancelled profile propagates cancellation rather than fabricating a save failure`() = runTest {
        val profile = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
        profile.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        val executor = ModelCheckBatch(
            profile,
            ModelCheckProbes(IsolatedPrompts { error("Profile closed") }),
            testScheduler.timeSource,
        )
        assertFailsWith<CancellationException> {
            executor.execute(request(save = { error("Closed profile must not save") }))
        }
    }
}

private fun request(
    save: suspend () -> String = { "saved" },
    revisions: suspend (String) -> List<ModelCheckRevision> = { listOf(checkReport().revision) },
    isCurrent: suspend (ModelCheckRevision) -> Boolean = { true },
): ModelCheckBatchRequest<String> = ModelCheckBatchRequest(checkReport().run, save, revisions, isCurrent)
