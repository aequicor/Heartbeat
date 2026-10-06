@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiProcessStopTest {
    @Test
    fun `termination request waits for both root and observed child exits`() = runTest {
        val child = StopChild()
        val process = StopProcess(child)
        val stop = PiProcessStop(process, dispatchers())
        stop.request()
        val waiting = async { stop.awaitStopped() }
        runCurrent()
        assertTrue(process.isStopRequested)
        assertTrue(child.isStopRequested)
        assertFalse(waiting.isCompleted)
        process.exit()
        runCurrent()
        assertFalse(waiting.isCompleted)
        child.exit()
        assertTrue(waiting.await())
    }

    @Test
    fun `timeout preserves observed children and retry waits for their exit`() = runTest {
        val child = StopChild()
        val process = StopProcess(child)
        val stop = PiProcessStop(process, dispatchers(), timeoutMillis = 100)
        val waiting = async { stop.awaitStopped() }
        runCurrent()
        process.exit()
        advanceTimeBy(100)
        runCurrent()
        assertFalse(waiting.await())
        // Once the parent exited, OS enumeration no longer lists its former child.
        process.isChildVisible = false
        val retry = async { stop.awaitStopped() }
        runCurrent()
        assertFalse(retry.isCompleted)
        child.exit()
        assertTrue(retry.await())
    }

    @Test
    fun `cancelled wait does not erase the proof required by a later waiter`() = runTest {
        val process = StopProcess()
        val stop = PiProcessStop(process, dispatchers())
        val waiting = async { stop.awaitStopped() }
        runCurrent()
        waiting.cancelAndJoin()
        assertTrue(process.isAlive)
        process.exit()
        assertTrue(stop.awaitStopped())
    }

    @Test
    fun `a child discovered by another waiter prevents stale confirmation`() = runTest {
        val child = StopChild()
        val process = StopProcess(child).apply { isChildVisible = false }
        val stop = PiProcessStop(process, dispatchers())
        val first = async { stop.awaitStopped() }
        runCurrent()
        process.isChildVisible = true
        val second = async { stop.awaitStopped() }
        runCurrent()
        process.exit()
        runCurrent()
        assertFalse(first.await())
        assertFalse(second.isCompleted)
        child.exit()
        assertTrue(second.await())
    }

    @Test
    fun `denied child enumeration still requests root stop without claiming confirmation`() = runTest {
        val process = StopProcess().apply { isEnumerationAllowed = false }
        val stop = PiProcessStop(process, dispatchers())
        assertFalse(stop.awaitStopped())
        assertTrue(process.isStopRequested)
        process.exit()
        assertFalse(stop.awaitStopped())
    }

    private fun TestScope.dispatchers(): DispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        override val io: CoroutineDispatcher = main
        override val default: CoroutineDispatcher = main
    }
}

private class StopProcess(private val child: StopChild? = null) : Process() {
    private val exited = CompletableFuture<Process>()
    private val root = PiTestProcessHandle({ isAlive }, { isStopRequested = true }, exited)
    var isStopRequested = false
    var isChildVisible = true
    var isEnumerationAllowed = true
    fun exit() {
        exited.complete(this)
    }
    override fun onExit(): CompletableFuture<Process> = exited.thenApply { this }
    override fun isAlive(): Boolean = !exited.isDone
    override fun toHandle(): ProcessHandle = root
    override fun destroy(): Unit = error("Process.destroy may block on its pipes; use the OS handle")
    override fun destroyForcibly(): Process = apply { destroy() }
    override fun descendants(): Stream<ProcessHandle> {
        check(isEnumerationAllowed) { "Enumeration denied" }
        return if (isChildVisible && child != null) Stream.of(child) else Stream.empty()
    }
    override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun waitFor(): Int = error("Use onExit")
    override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0
}

private class StopChild : ProcessHandle {
    private val exited = CompletableFuture<ProcessHandle>()
    var isStopRequested = false
    fun exit() {
        exited.complete(this)
    }
    override fun pid(): Long = 123
    override fun parent(): Optional<ProcessHandle> = Optional.empty()
    override fun children(): Stream<ProcessHandle> = Stream.empty()
    override fun descendants(): Stream<ProcessHandle> = Stream.empty()
    override fun info(): ProcessHandle.Info = error("Unused")
    override fun onExit(): CompletableFuture<ProcessHandle> = exited.thenApply { this }
    override fun supportsNormalTermination(): Boolean = true
    override fun isAlive(): Boolean = !exited.isDone
    override fun destroy(): Boolean {
        isStopRequested = true
        return true
    }
    override fun destroyForcibly(): Boolean = destroy()
    override fun compareTo(other: ProcessHandle): Int = pid().compareTo(other.pid())
}
