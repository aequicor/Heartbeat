package io.aequicor.heartbeat.core.desktopdialogs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DialogThreadTest {
    @Test
    fun `the event queue keeps dispatching while the native thread waits`() = runTest {
        val eventDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = EventQueue.invokeLater(block)
        }
        val worker = withContext(eventDispatcher) {
            assertTrue(EventQueue.isDispatchThread())
            onDialogThread {
                assertFalse(EventQueue.isDispatchThread())
                val dispatched = CountDownLatch(1)
                EventQueue.invokeLater { dispatched.countDown() }
                assertTrue(dispatched.await(10, TimeUnit.SECONDS), "EDT stopped while the dialog was open")
                Thread.currentThread()
            }
        }
        worker.join(10_000)
        assertFalse(worker.isAlive)
    }

    @Test
    fun `a native linkage error resumes the caller unchanged`() = runTest {
        val failure = UnsatisfiedLinkError("native backend unavailable")
        assertSame(failure, assertFailsWith<UnsatisfiedLinkError> { onDialogThread { throw failure } })
    }

    @Test
    fun `cancellation waits for dialog cleanup and the native thread exits`() = runTest {
        val entered = CompletableDeferred<Thread>()
        val dismiss = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val call = launch {
            onDialogThread {
                entered.complete(Thread.currentThread())
                try {
                    assertTrue(dismiss.await(10, TimeUnit.SECONDS))
                } finally {
                    cleaned.countDown()
                }
            }
        }
        runCurrent()
        val worker = entered.await()
        try {
            call.cancel()
            runCurrent()
            assertFalse(call.isCompleted)
        } finally {
            dismiss.countDown()
        }
        call.join()
        assertTrue(cleaned.await(10, TimeUnit.SECONDS))
        worker.join(10_000)
        assertFalse(worker.isAlive)
    }
}
