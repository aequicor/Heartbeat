package io.aequicor.heartbeat.feature.computeruse.impl.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopProcessTest {
    @Test
    fun `cancelling a desktop command destroys its process and closes every stream`() = runTest {
        val process = FakeProcess()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runDesktopProcess(listOf("fake"), timeoutMillis = 10_000) { process }
        }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertTrue(process.wasDestroyed)
        assertTrue(process.stdout.isClosed && process.stderr.isClosed && process.stdin.isClosed)
    }

    @Test
    fun `timed out desktop command is cleaned up`() = runTest {
        val process = FakeProcess()
        assertFalse(runDesktopProcess(listOf("fake"), timeoutMillis = 100) { process })
        assertTrue(process.wasDestroyed)
        assertTrue(process.stdout.isClosed && process.stderr.isClosed && process.stdin.isClosed)
    }

    @Test
    fun `successful desktop command also closes every stream`() = runTest {
        val process = FakeProcess().apply { alive = false }
        assertTrue(runDesktopProcess(listOf("fake"), timeoutMillis = 100) { process })
        assertFalse(process.wasDestroyed)
        assertTrue(process.stdout.isClosed && process.stderr.isClosed && process.stdin.isClosed)
    }

    private class FakeProcess : Process() {
        val stdout = TrackedInput()
        val stderr = TrackedInput()
        val stdin = TrackedOutput()
        var alive = true
        var wasDestroyed = false

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun isAlive(): Boolean = alive
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() {
            wasDestroyed = true
            alive = false
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    private class TrackedInput : ByteArrayInputStream(byteArrayOf()) {
        var isClosed = false
        override fun close() {
            isClosed = true
            super.close()
        }
    }

    private class TrackedOutput : ByteArrayOutputStream() {
        var isClosed = false
        override fun close() {
            isClosed = true
            super.close()
        }
    }
}
