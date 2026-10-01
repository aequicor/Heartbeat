package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
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
}

private class PresentationWindow(var isVisible: Boolean = true) : ComputerUsePresentation {
    var restorations = 0
    var onSuppressed: () -> Unit = {}
    var onRestored: () -> Unit = {}
    private var isDisposed = false

    override fun suppress(): AutoCloseable {
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
