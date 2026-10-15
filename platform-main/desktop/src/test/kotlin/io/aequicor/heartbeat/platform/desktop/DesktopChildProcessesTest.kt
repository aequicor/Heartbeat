package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChildProcessesTest {
    @Test
    fun `captured child is stopped after its parent exits during resource cleanup`() = runTest {
        val parent = ChildProcess(1)
        val child = ChildProcess(2)
        val root = ChildProcess(0).apply { observed = listOf(parent, child) }
        val cleanup = cleanup(root)
        cleanup.capture()
        assertEquals(0, child.stopRequests)

        parent.exit()
        root.observed = emptyList()
        cleanup.stop()

        assertFalse(child.isAlive)
        assertEquals(1, child.stopRequests)
        assertEquals(0, parent.stopRequests)
        assertEquals(0, root.stopRequests)
    }

    @Test
    fun `new descendants of a retained reparented process are stopped too`() = runTest {
        val parent = ChildProcess(1)
        val child = ChildProcess(2)
        val root = ChildProcess(0).apply { observed = listOf(parent) }
        val cleanup = cleanup(root)
        cleanup.capture()

        root.observed = emptyList()
        parent.observed = listOf(child)
        cleanup.stop()

        assertFalse(parent.isAlive)
        assertFalse(child.isAlive)
        assertEquals(1, parent.stopRequests)
        assertEquals(1, child.stopRequests)
        assertEquals(0, root.stopRequests)
    }

    @Test
    fun `failure to kill one child does not prevent stopping its siblings`() = runTest {
        val sibling = ChildProcess(1)
        val denied = ChildProcess(2).apply { stopFailure = SecurityException("Termination denied") }
        val root = ChildProcess(0).apply { observed = listOf(sibling, denied) }

        assertFailsWith<IllegalStateException> { cleanup(root).stop() }

        assertEquals(1, denied.stopRequests)
        assertTrue(denied.isAlive)
        assertEquals(1, sibling.stopRequests)
        assertFalse(sibling.isAlive)
    }

    @Test
    fun `shutdown waits for actual exit after termination was requested`() = runTest {
        val child = ChildProcess(1, exitsOnStop = false)
        val root = ChildProcess(0).apply { observed = listOf(child) }
        val cleanup = cleanup(root)
        val stopping = async { cleanup.stop() }
        runCurrent()

        assertEquals(1, child.stopRequests)
        assertTrue(child.isAlive)
        assertFalse(stopping.isCompleted)

        child.exit()
        stopping.await()
        assertFalse(child.isAlive)
    }

    @Test
    fun `a child that never exits fails within the shutdown bound`() = runTest {
        val child = ChildProcess(1, exitsOnStop = false)
        val root = ChildProcess(0).apply { observed = listOf(child) }

        val failure = assertFailsWith<IllegalStateException> { cleanup(root).stop() }

        assertTrue(failure.message.orEmpty().contains("1000 ms"))
        assertEquals(1_000L, currentTime)
        assertEquals(1, child.stopRequests)
        assertTrue(child.isAlive)
    }

    private fun TestScope.cleanup(root: ProcessHandle): DesktopChildProcesses {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
        }
        return DesktopChildProcesses(dispatchers, root, timeoutMillis = 1_000)
    }

    private class ChildProcess(private val id: Long, private val exitsOnStop: Boolean = true) : ProcessHandle {
        private val exited = CompletableFuture<ProcessHandle>()
        var observed: List<ProcessHandle> = emptyList()
        var stopFailure: Exception? = null
        var stopRequests = 0

        fun exit() {
            exited.complete(this)
        }

        override fun pid(): Long = id
        override fun parent(): Optional<ProcessHandle> = Optional.empty()
        override fun children(): Stream<ProcessHandle> = observed.stream()
        override fun descendants(): Stream<ProcessHandle> = observed.stream()
        override fun info(): ProcessHandle.Info = object : ProcessHandle.Info {
            override fun startInstant(): Optional<Instant> = Optional.of(Instant.EPOCH)
            override fun command(): Optional<String> = Optional.empty()
            override fun commandLine(): Optional<String> = Optional.empty()
            override fun arguments(): Optional<Array<String>> = Optional.empty()
            override fun totalCpuDuration(): Optional<Duration> = Optional.empty()
            override fun user(): Optional<String> = Optional.empty()
        }
        override fun onExit(): CompletableFuture<ProcessHandle> = exited.thenApply { this }
        override fun supportsNormalTermination(): Boolean = true
        override fun isAlive(): Boolean = !exited.isDone
        override fun destroy(): Boolean = destroyForcibly()
        override fun destroyForcibly(): Boolean {
            stopRequests++
            stopFailure?.let { throw it }
            if (exitsOnStop) exit()
            return true
        }
        override fun compareTo(other: ProcessHandle): Int = pid().compareTo(other.pid())
    }
}
