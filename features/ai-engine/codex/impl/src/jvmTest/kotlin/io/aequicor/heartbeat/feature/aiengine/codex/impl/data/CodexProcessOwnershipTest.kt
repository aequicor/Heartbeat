package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CodexProcessOwnershipTest {
    @Test
    fun `captures nanosecond identity and retains reparented descendants per launch`() = runTest {
        val root = TestProcess(10)
        val child = TestProcess(11)
        root.observed = listOf(child)
        val tracker = tracker(root)
        val first = assertNotNull(tracker.capture())
        assertEquals("2026-10-06T00:00:00.123456789Z", first.root.startedAt)
        assertEquals(listOf(11L), first.observedChildren.map { it.pid })
        root.observed = emptyList()
        val next = assertNotNull(tracker.capture())
        assertEquals(first, next)
        assertNotEquals(first.launchId, assertNotNull(tracker(root).capture()).launchId)
    }

    @Test
    fun `missing root or child start time permanently removes stop evidence for this launch`() = runTest {
        for (isChild in listOf(false, true)) {
            val root = TestProcess(10)
            val target = if (isChild) TestProcess(11).also { root.observed = listOf(it) } else root
            target.startedAt = null
            val tracker = tracker(root)
            assertNull(tracker.capture())
            target.startedAt = Instant.parse("2026-10-06T00:00:00Z")
            assertNull(tracker.capture())
        }
    }

    @Test
    fun `denied descendants or dead root cannot become an empty complete snapshot`() = runTest {
        val root = TestProcess(10)
        root.canEnumerate = false
        val tracker = tracker(root)
        assertNull(tracker.capture())
        root.canEnumerate = true
        assertNull(tracker.capture())
        root.alive = false
        assertNull(tracker(root).capture())
    }

    @Test
    fun `real JVM process identity matches the OS without any termination call`() = runTest {
        val handle = ProcessHandle.current()
        val owner = assertNotNull(CodexProcessOwnership({ handle }, dispatchers()).capture())
        assertEquals(handle.pid(), owner.root.pid)
        assertEquals(handle.info().startInstant().orElseThrow().toString(), owner.root.startedAt)
    }

    private fun TestScope.tracker(root: TestProcess): CodexProcessOwnership =
        CodexProcessOwnership({ root }, dispatchers())

    private fun TestScope.dispatchers(): DispatcherProvider = object : DispatcherProvider {
        private val dispatcher = StandardTestDispatcher(testScheduler)
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }

    /** No fake method can signal or look up a real process. */
    private class TestProcess(private val id: Long) : ProcessHandle {
        var startedAt: Instant? = Instant.parse("2026-10-06T00:00:00.123456789Z")
        var observed: List<ProcessHandle> = emptyList()
        var alive = true
        var canEnumerate = true
        override fun pid(): Long = id
        override fun parent(): Optional<ProcessHandle> = Optional.empty()
        override fun children(): Stream<ProcessHandle> = observed.stream()
        override fun descendants(): Stream<ProcessHandle> {
            if (!canEnumerate) throw SecurityException("private native detail")
            return observed.stream()
        }
        override fun info(): ProcessHandle.Info = object : ProcessHandle.Info {
            override fun startInstant(): Optional<Instant> = Optional.ofNullable(startedAt)
            override fun command(): Optional<String> = error("Unused")
            override fun commandLine(): Optional<String> = error("Unused")
            override fun arguments(): Optional<Array<String>> = error("Unused")
            override fun totalCpuDuration(): Optional<Duration> = error("Unused")
            override fun user(): Optional<String> = error("Unused")
        }
        override fun onExit(): CompletableFuture<ProcessHandle> = error("Capture must not wait")
        override fun supportsNormalTermination(): Boolean = error("Unused")
        override fun isAlive(): Boolean = alive
        override fun destroy(): Boolean = error("Capture must not signal")
        override fun destroyForcibly(): Boolean = error("Capture must not signal")
        override fun compareTo(other: ProcessHandle): Int = pid().compareTo(other.pid())
    }
}
