package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessCodexWireTest {
    @Test
    fun `close stops descendants and process, closes stdin and releases once`() {
        val process = FakeProcess()
        var releases = 0
        val wire = ProcessCodexWire(process, TestDispatchers) { releases++ }

        wire.close()
        wire.close()

        assertTrue(process.areDescendantsRequested)
        assertEquals(1, process.destroyed)
        assertTrue(process.stdin.closed.await(5, TimeUnit.SECONDS))
        assertEquals(1, releases)
    }

    @Test
    fun `close still stops process when descendants are unsupported`() {
        val process = FakeProcess(areDescendantsSupported = false)
        val wire = ProcessCodexWire(process, TestDispatchers) {}

        wire.close()

        assertEquals(1, process.destroyed)
        assertTrue(process.stdin.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `close during in-flight write closes stdin once the write finishes`() {
        val process = FakeProcess()
        val wire = ProcessCodexWire(process, TestDispatchers) {}
        val writer = thread { runBlocking { wire.write(JsonObject(emptyMap())) } }
        assertTrue(process.stdin.entered.await(5, TimeUnit.SECONDS))

        wire.close()
        assertFalse(process.stdin.isClosed)
        process.stdin.proceed.countDown()
        writer.join(5_000)

        assertFalse(writer.isAlive)
        assertTrue(process.stdin.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `close requests exit without closing stdin before process death`() {
        val process = FakeProcess(exitsOnSignal = false)
        var releases = 0
        val wire = ProcessCodexWire(process, TestDispatchers) { releases++ }
        wire.close()
        assertEquals(1, process.destroyed)
        assertEquals(1, releases)
        assertEquals(1L, process.stdin.closing.count)
        process.exit()
        assertTrue(process.stdin.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `blocking stdin cleanup runs on IO after close returns and releases registration`() {
        val process = FakeProcess()
        val cleanup = CountDownLatch(1)
        process.stdin.closeGate = cleanup
        var releases = 0
        val wire = ProcessCodexWire(process, TestDispatchers) { releases++ }
        try {
            wire.close()
            assertEquals(1, releases)
            assertTrue(process.stdin.closing.await(5, TimeUnit.SECONDS))
            assertFalse(process.stdin.isClosed)
        } finally {
            cleanup.countDown()
        }
        assertTrue(process.stdin.closed.await(5, TimeUnit.SECONDS))
    }

    private object TestDispatchers : DispatcherProvider {
        override val main = Dispatchers.Default
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    private class TrackingStream : OutputStream() {
        @Volatile var isClosed = false
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var closeGate: CountDownLatch? = null

        override fun write(b: Int) {
            entered.countDown()
            proceed.await(5, TimeUnit.SECONDS)
        }

        override fun close() {
            closing.countDown()
            closeGate?.await(5, TimeUnit.SECONDS)
            isClosed = true
            closed.countDown()
        }
    }

    private class FakeProcess(
        private val areDescendantsSupported: Boolean = true,
        private val exitsOnSignal: Boolean = true,
    ) : Process() {
        val stdin = TrackingStream()
        var destroyed = 0
        var areDescendantsRequested = false
        private val exited = CompletableFuture<Process>()
        fun exit() {
            exited.complete(this)
        }
        override fun isAlive(): Boolean = !exited.isDone
        override fun onExit(): CompletableFuture<Process> = exited.thenApply { it }
        override fun toHandle(): ProcessHandle = object : ProcessHandle {
            override fun pid(): Long = 1
            override fun parent(): Optional<ProcessHandle> = Optional.empty()
            override fun children(): Stream<ProcessHandle> = Stream.empty()
            override fun descendants(): Stream<ProcessHandle> = this@FakeProcess.descendants()
            override fun info(): ProcessHandle.Info = error("Unused")
            override fun onExit(): CompletableFuture<ProcessHandle> = exited.thenApply { this }
            override fun supportsNormalTermination(): Boolean = true
            override fun isAlive(): Boolean = this@FakeProcess.isAlive
            override fun destroy(): Boolean {
                destroyed++
                if (exitsOnSignal) exit()
                return true
            }
            override fun destroyForcibly(): Boolean = destroy()
            override fun compareTo(other: ProcessHandle): Int = pid().compareTo(other.pid())
        }

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy(): Unit = error("Process.destroy can block closing stdin; use its handle")

        override fun destroyForcibly(): Process = apply { destroy() }

        override fun descendants(): Stream<ProcessHandle> {
            areDescendantsRequested = true
            if (!areDescendantsSupported) throw UnsupportedOperationException("unsupported in test")
            return Stream.empty()
        }
    }
}
