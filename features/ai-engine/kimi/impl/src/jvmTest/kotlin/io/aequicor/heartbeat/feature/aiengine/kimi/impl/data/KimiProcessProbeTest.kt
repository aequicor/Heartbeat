package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KimiProcessProbeTest {
    @Test
    fun `successful probe returns exit code and releases resources`() = runTest {
        val process = ProbeProcess(isFinished = true)
        assertEquals(0, probeKimiProcess(dispatchers, 5_000) { process })
        assertTrue(process.isDestroyed.get())
    }

    @Test
    fun `hung probe times out and releases process`() = runTest {
        val process = ProbeProcess()
        assertNull(probeKimiProcess(dispatchers, 100) { process })
        assertTrue(process.isDestroyed.get())
    }

    @Test
    fun `caller cancellation releases process and remains cancellation`() = runTest {
        val process = ProbeProcess()
        val started = CompletableDeferred<Unit>()
        val result = async {
            probeKimiProcess(dispatchers, 5_000) {
                started.complete(Unit)
                process
            }
        }
        started.await()
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
        assertTrue(process.isDestroyed.get())
    }

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Default
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }
}

internal class ProbeProcess(isFinished: Boolean = false) : Process() {
    val isDestroyed = AtomicBoolean()
    private val finished = CountDownLatch(if (isFinished) 0 else 1)
    override fun getOutputStream() = ByteArrayOutputStream()
    override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
    override fun waitFor(): Int {
        finished.await()
        return 0
    }
    override fun exitValue(): Int = if (finished.count == 0L) 0 else throw IllegalThreadStateException()
    override fun destroy() {
        isDestroyed.set(true)
        finished.countDown()
    }
    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
    override fun descendants(): Stream<ProcessHandle> = Stream.empty()
}
