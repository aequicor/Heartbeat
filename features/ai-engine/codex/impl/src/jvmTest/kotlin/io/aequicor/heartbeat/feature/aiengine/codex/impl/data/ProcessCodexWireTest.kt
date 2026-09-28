package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
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

        assertTrue(process.descendantsRequested)
        assertEquals(1, process.destroyed)
        assertTrue(process.stdin.closed)
        assertEquals(1, releases)
    }

    @Test
    fun `close still stops process when descendants are unsupported`() {
        val process = FakeProcess(descendantsSupported = false)
        val wire = ProcessCodexWire(process, TestDispatchers) {}

        wire.close()

        assertEquals(1, process.destroyed)
        assertTrue(process.stdin.closed)
    }

    @Test
    fun `close during in-flight write closes stdin once the write finishes`() {
        val process = FakeProcess()
        val wire = ProcessCodexWire(process, TestDispatchers) {}
        val writer = thread { runBlocking { wire.write(JsonObject(emptyMap())) } }
        assertTrue(process.stdin.entered.await(5, TimeUnit.SECONDS))

        wire.close()
        assertFalse(process.stdin.closed)
        process.stdin.proceed.countDown()
        writer.join(5_000)

        assertFalse(writer.isAlive)
        assertTrue(process.stdin.closed)
    }

    private object TestDispatchers : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    private class TrackingStream : OutputStream() {
        @Volatile var closed = false
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)

        override fun write(b: Int) {
            entered.countDown()
            proceed.await(5, TimeUnit.SECONDS)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeProcess(private val descendantsSupported: Boolean = true) : Process() {
        val stdin = TrackingStream()
        var destroyed = 0
        var descendantsRequested = false

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() {
            destroyed++
        }

        override fun destroyForcibly(): Process = apply { destroy() }

        override fun descendants(): Stream<ProcessHandle> {
            descendantsRequested = true
            if (!descendantsSupported) throw UnsupportedOperationException("unsupported in test")
            return Stream.empty()
        }
    }
}
