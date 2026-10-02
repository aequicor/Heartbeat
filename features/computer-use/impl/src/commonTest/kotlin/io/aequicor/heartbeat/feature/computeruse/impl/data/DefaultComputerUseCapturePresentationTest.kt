package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseSuppressionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultComputerUseCapturePresentationTest {
    @Test
    fun `pointer suppression keeps its reason for controllers registered mid action`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val reasons = mutableListOf<ComputerUseSuppressionReason>()
        val indicator = object : ComputerUsePresentation {
            override fun suppress(): AutoCloseable = error("A reason must be provided")
            override fun suppress(reason: ComputerUseSuppressionReason): AutoCloseable {
                reasons += reason
                return AutoCloseable { }
            }
        }
        presentation.register(indicator)
        presentation.withoutPresentation(ComputerUseSuppressionReason.PointerInput) {
            val late = presentation.register(indicator)
            late.close()
        }
        presentation.withoutPresentation { }
        assertEquals(
            listOf(
                ComputerUseSuppressionReason.PointerInput,
                ComputerUseSuppressionReason.PointerInput,
                ComputerUseSuppressionReason.CapturePixels,
            ),
            reasons,
        )
    }

    @Test
    fun `capture sees no app windows and restores their original visibility after success or failure`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val visible = PresentationWindow()
        val hidden = PresentationWindow(isVisible = false)
        presentation.register(visible)
        presentation.register(hidden)
        assertEquals(
            "frame",
            presentation.withoutPresentation {
                assertFalse(visible.isVisible)
                assertFalse(hidden.isVisible)
                "frame"
            },
        )
        assertTrue(visible.isVisible)
        assertFalse(hidden.isVisible)
        assertFailsWith<IllegalStateException> {
            presentation.withoutPresentation {
                assertFalse(visible.isVisible)
                error("capture failed")
            }
        }
        assertTrue(visible.isVisible)
        assertFalse(hidden.isVisible)
    }

    @Test
    fun `cancellation restores the app before the operation finishes`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val window = PresentationWindow()
        presentation.register(window)
        val entered = CompletableDeferred<Unit>()
        val capture = launch {
            presentation.withoutPresentation {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        assertFalse(window.isVisible)
        capture.cancelAndJoin()
        assertTrue(window.isVisible)
    }

    @Test
    fun `cancellation during main thread acquisition cannot strand a hidden app window`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val window = PresentationWindow()
        presentation.register(window)
        var isCaptureCalled = false
        val capture = launch { presentation.withoutPresentation { isCaptureCalled = true } }
        window.onSuppressed = { capture.cancel() }
        capture.join()
        assertFalse(isCaptureCalled)
        assertTrue(window.isVisible)
    }

    @Test
    fun `new presentation stays excluded until cleanup even after unregistering`() = runTest {
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        val presentation = DefaultComputerUseCapturePresentation(dispatchers)
        val first = PresentationWindow()
        presentation.register(first)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val capture = launch {
            presentation.withoutPresentation {
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        val later = PresentationWindow()
        withContext(dispatchers.main) {
            val registration = presentation.register(later)
            assertFalse(later.isVisible)
            registration.close()
            assertEquals(0, later.restorations)
            later.dispose()
        }
        finish.complete(Unit)
        capture.join()
        assertTrue(first.isVisible)
        assertFalse(later.isVisible)
        assertEquals(1, later.restorations)
    }

    @Test
    fun `overlapping operations cannot reveal the app during another capture`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val window = PresentationWindow()
        presentation.register(window)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = launch {
            presentation.withoutPresentation {
                entered.complete(Unit)
                finish.await()
                assertFalse(window.isVisible)
            }
        }
        entered.await()
        var isSecondEntered = false
        val second = launch {
            presentation.withoutPresentation {
                isSecondEntered = true
                assertFalse(window.isVisible)
            }
        }
        runCurrent()
        assertFalse(isSecondEntered)
        finish.complete(Unit)
        first.join()
        second.join()
        assertTrue(isSecondEntered)
        assertTrue(window.isVisible)
    }

    @Test
    fun `failed exclusion rolls back windows already hidden and refuses capture`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val window = PresentationWindow()
        presentation.register(window)
        presentation.register { error("window cannot be excluded") }
        var isCaptureCalled = false
        assertFailsWith<IllegalStateException> {
            presentation.withoutPresentation { isCaptureCalled = true }
        }
        assertFalse(isCaptureCalled)
        assertTrue(window.isVisible)
    }

    @Test
    fun `restoration drains every lease and propagates cancellation before an ordinary failure`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val window = PresentationWindow()
        val cancelled = PresentationWindow()
        val failed = PresentationWindow()
        val nativeFailure = PresentationWindow()
        val cancellation = CancellationException("restore cancelled")
        cancelled.onRestored = { throw cancellation }
        failed.onRestored = { error("restore failed") }
        nativeFailure.onRestored = { throw AssertionError("native restore failed") }
        presentation.register(window)
        presentation.register(cancelled)
        presentation.register(failed)
        presentation.register(nativeFailure)
        val failure = assertFailsWith<CancellationException> {
            presentation.withoutPresentation { assertFalse(window.isVisible) }
        }
        // Coroutine stack-trace recovery can copy an exception at a dispatcher boundary.
        assertEquals(cancellation.message, failure.message)
        listOf(window, cancelled, failed, nativeFailure).forEach {
            assertTrue(it.isVisible)
            assertEquals(1, it.restorations)
        }
    }

    @Test
    fun `operation failure stays primary and carries the restoration failure`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val window = PresentationWindow()
        window.onRestored = { error("restore failed") }
        presentation.register(window)
        val failure = assertFailsWith<IllegalArgumentException> {
            presentation.withoutPresentation { throw IllegalArgumentException("capture failed") }
        }
        assertEquals("capture failed", failure.message)
        assertEquals(listOf("restore failed"), failure.suppressedMessages())
        assertEquals(1, window.restorations)
    }

    @Test
    fun `cancelled operation stays cancelled when restoration fails`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val window = PresentationWindow()
        window.onRestored = { error("restore failed") }
        presentation.register(window)
        val entered = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        val capture = launch {
            try {
                presentation.withoutPresentation {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            } catch (e: CancellationException) {
                failure = e
                throw e
            }
        }
        entered.await()
        capture.cancelAndJoin()
        assertTrue(failure is CancellationException)
        assertEquals(listOf("restore failed"), failure?.suppressedMessages())
        assertTrue(window.isVisible)
    }

    @Test
    fun `failed exclusion keeps its cause when rolling back also fails`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val window = PresentationWindow()
        window.onRestored = { error("rollback failed") }
        presentation.register(window)
        presentation.register { throw IllegalArgumentException("window cannot be excluded") }
        val failure = assertFailsWith<IllegalArgumentException> { presentation.withoutPresentation { } }
        assertEquals("window cannot be excluded", failure.message)
        assertEquals(listOf("rollback failed"), failure.suppressedMessages())
        assertEquals(1, window.restorations)
    }

    @Test
    fun `window that cannot be excluded during capture stays registered for the next capture`() = runTest {
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        val presentation = DefaultComputerUseCapturePresentation(dispatchers)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val capture = launch {
            presentation.withoutPresentation {
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        val late = PresentationWindow()
        late.refusal = "not yet mapped"
        withContext(dispatchers.main) { presentation.register(late) }
        finish.complete(Unit)
        capture.join()
        late.refusal = null
        presentation.withoutPresentation { assertFalse(late.isVisible) }
        assertTrue(late.isVisible)
    }

    @Test
    fun `cancellation while excluding a late window leaves no registration behind`() = runTest {
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        val presentation = DefaultComputerUseCapturePresentation(dispatchers)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val capture = launch {
            presentation.withoutPresentation {
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        var suppressions = 0
        withContext(dispatchers.main) {
            assertFailsWith<CancellationException> {
                presentation.register {
                    suppressions++
                    throw CancellationException("window gone")
                }
            }
        }
        finish.complete(Unit)
        capture.join()
        presentation.withoutPresentation { }
        assertEquals(1, suppressions)
    }

    @Test
    fun `closed registration is not excluded by later captures`() = runTest {
        val presentation = DefaultComputerUseCapturePresentation(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val window = PresentationWindow()
        presentation.register(window).close()
        presentation.withoutPresentation { assertTrue(window.isVisible) }
        assertEquals(0, window.restorations)
    }
}

/** Stack-trace recovery may wrap the thrown instance at a dispatcher boundary, keeping the original as its cause. */
private fun Throwable.suppressedMessages(): List<String?> =
    generateSequence(this) { it.cause }.flatMap { it.suppressedExceptions }.map { it.message }.toList()

private class PresentationWindow(var isVisible: Boolean = true) : ComputerUsePresentation {
    var restorations = 0
    var onSuppressed: () -> Unit = {}
    var onRestored: () -> Unit = {}

    /** Refuses exclusion while keeping the original visibility, as the contract requires. */
    var refusal: String? = null
    private var isDisposed = false

    override fun suppress(): AutoCloseable {
        refusal?.let(::error)
        val wasVisible = isVisible
        isVisible = false
        onSuppressed()
        return AutoCloseable {
            restorations++
            if (!isDisposed) isVisible = wasVisible
            onRestored()
        }
    }

    fun dispose() {
        isDisposed = true
        isVisible = false
    }
}
