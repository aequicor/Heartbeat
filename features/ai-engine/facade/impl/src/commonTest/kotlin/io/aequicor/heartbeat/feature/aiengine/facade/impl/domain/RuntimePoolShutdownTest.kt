package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultRuntimePoolShutdown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RuntimePoolShutdownTest {
    @Test
    fun `application shutdown waits for cleanup of an already closed profile`() = runTest {
        val shutdown = DefaultRuntimePoolShutdown()
        val stopped = CompletableDeferred<Unit>()
        var closes = 0
        val cleanup = shutdown.register {
            closes++
            stopped.await()
        }
        val profileClose = async { cleanup() }
        runCurrent()
        val appClose = async { shutdown.shutdown() }
        runCurrent()

        assertFalse(appClose.isCompleted)
        assertEquals(1, closes)
        stopped.complete(Unit)
        profileClose.await()
        appClose.await()
        shutdown.shutdown()
        assertEquals(1, closes)
    }

    @Test
    fun `shutdown attempts all pools and retries only failures`() = runTest {
        val shutdown = DefaultRuntimePoolShutdown()
        var shouldFail = true
        var failedCloses = 0
        var completedCloses = 0
        shutdown.register {
            failedCloses++
            check(!shouldFail) { "stop failed" }
        }
        shutdown.register { completedCloses++ }

        assertEquals("stop failed", assertFailsWith<IllegalStateException> { shutdown.shutdown() }.message)
        assertEquals(1, completedCloses)
        shouldFail = false
        shutdown.shutdown()
        assertEquals(2, failedCloses)
        assertEquals(1, completedCloses)
    }

    @Test
    fun `shutdown without used pools creates nothing and rejects later startup`() = runTest {
        val shutdown = DefaultRuntimePoolShutdown()
        shutdown.shutdown()

        val error = assertFailsWith<EngineException> { shutdown.register { error("must not run") } }

        assertEquals(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed), error.failure)
    }
}
