package io.aequicor.heartbeat.feature.aistudio.impl.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioHelperPromptGuardTest {
    @Test
    fun `initial durable admission finishes before preparation proceeds`() = runTest {
        val release = CompletableDeferred<Unit>()
        val guard = StudioHelperPromptGuard(
            flow {
                release.await()
                emit(true)
            },
        )
        var prepared = false
        val result = async {
            guard.watch {
                prepared = true
                guard.submitted()
            }
        }
        runCurrent()
        assertFalse(prepared)
        release.complete(Unit)
        result.await()
        assertTrue(guard.isSubmitted)
    }

    @Test
    fun `live false cannot be revived by a new cold allow`() = runTest {
        val changes = MutableSharedFlow<Boolean>()
        var collections = 0
        val guard = StudioHelperPromptGuard(
            flow {
                emit(true)
                if (collections++ == 0) changes.collect { emit(it) }
            },
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async {
            guard.watch {
                entered.complete(Unit)
                release.await()
                guard.submitted()
            }
        }
        entered.await()
        changes.emit(false)
        release.complete(Unit)
        assertFailsWith<CancellationException> { result.await() }
        assertFalse(guard.isSubmitted)
    }

    @Test
    fun `late observer failure cannot cancel a native acknowledgement wait`() = runTest {
        val crash = CompletableDeferred<Unit>()
        var collections = 0
        val guard = StudioHelperPromptGuard(
            flow {
                emit(true)
                if (collections++ == 0) {
                    crash.await()
                    error("private observer failure")
                }
            },
        )
        val submitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async {
            guard.watch {
                guard.submitted()
                submitted.complete(Unit)
                release.await()
            }
        }
        submitted.await()
        crash.complete(Unit)
        runCurrent()
        assertFalse(result.isCancelled)
        release.complete(Unit)
        result.await()
        assertTrue(guard.isSubmitted)
    }

    @Test
    fun `self cancellation and empty initial source refuse before native send`() = runTest {
        val cancelled = StudioHelperPromptGuard(flow { throw CancellationException("source") })
        val first = async { cancelled.watch { error("must not prepare") } }
        assertFailsWith<CancellationException> { first.await() }
        val empty = StudioHelperPromptGuard(flowOf())
        val second = async { empty.watch { error("must not prepare") } }
        assertFailsWith<CancellationException> { second.await() }
        val waiting = StudioHelperPromptGuard(flow { awaitCancellation() })
        val third = async { waiting.watch { error("must not prepare") } }
        // Cancel the caller while the first admission is still pending.
        runCurrent()
        third.cancel()
        assertFailsWith<CancellationException> { third.await() }
    }
}
