package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessDetachedCompilationTest {
    @Test
    fun `wait timeout leaves profile compiler owned until profile drain`() = runTest {
        val directory = Files.createTempDirectory("harness-timeout")
        val dispatcher = ManualCompilerDispatcher()
        val job = SupervisorJob()
        val host = JvmHarnessScriptHost(directory, "test", CoroutineScope(job + dispatcher), dispatcher.provider())
        try {
            val waiting = async { host.compile(request()) }
            runCurrent()
            assertEquals(1, dispatcher.pending.size)
            advanceTimeBy(90_000)
            runCurrent()
            assertEquals(HarnessCompilationResult.TimedOut, waiting.await())
            assertTrue(job.children.single().isActive)
            job.cancel()
            dispatcher.drain()
            job.join()
            assertFalse(Files.list(directory).use { it.findAny().isPresent })
        } finally {
            job.cancel()
            dispatcher.drain()
            job.join()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `remove fences queued compiler even after its caller cancels`() = runTest {
        val directory = Files.createTempDirectory("harness-remove")
        val dispatcher = ManualCompilerDispatcher()
        val job = SupervisorJob()
        val host = JvmHarnessScriptHost(directory, "test", CoroutineScope(job + dispatcher), dispatcher.provider())
        try {
            val waiting = async { host.compile(request()) }
            runCurrent()
            waiting.cancelAndJoin()
            val remove = async { host.removeCached(request().harness, request().item) }
            runCurrent()
            // Execute removal before the queued compiler begins; the caller no longer owns its token.
            dispatcher.pending.removeLast().run()
            runCurrent()
            remove.await()
            dispatcher.drain()
            assertFalse(Files.list(directory).use { it.findAny().isPresent })
        } finally {
            job.cancel()
            dispatcher.drain()
            job.join()
            directory.toFile().deleteRecursively()
        }
    }

    private fun request() = HarnessCompilationRequest(
        HarnessId("test"),
        ItemId("workflow"),
        HarnessCodeKind.Workflow,
        "workflow { input -> input }",
    )
}

private class ManualCompilerDispatcher : CoroutineDispatcher() {
    val pending = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        pending.addLast(block)
    }
    fun drain() {
        while (pending.isNotEmpty()) pending.removeFirst().run()
    }
    fun provider(): DispatcherProvider = object : DispatcherProvider {
        override val main = this@ManualCompilerDispatcher
        override val default = this@ManualCompilerDispatcher
        override val io = this@ManualCompilerDispatcher
    }
}
