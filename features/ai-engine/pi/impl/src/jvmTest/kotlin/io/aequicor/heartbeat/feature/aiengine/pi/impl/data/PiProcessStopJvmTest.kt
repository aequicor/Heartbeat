package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiProcessStopJvmTest {
    @Test
    fun `real JVM child remains unconfirmed until the operating system reports exit`() = runTest {
        val directory = Files.createTempDirectory("pi-stop-probe")
        val source = directory.resolve("OwnedStopProbe.java")
        Files.writeString(
            source,
            """
            class OwnedStopProbe {
                public static void main(String[] args) throws Exception {
                    System.out.write(1);
                    System.out.flush();
                    System.in.read();
                }
            }
            """.trimIndent(),
        )
        val binary = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", binary)
        val process = ProcessBuilder(java.toString(), source.toString()).start()
        try {
            withContext(Dispatchers.IO) {
                withTimeout(PROBE_TIMEOUT) {
                    assertEquals(1, runInterruptible { process.inputStream.read() })
                    val delayed = DelayedNativeStop(process)
                    val dispatchers = object : DispatcherProvider {
                        override val main = Dispatchers.Default
                        override val default = Dispatchers.Default
                        override val io = Dispatchers.IO
                    }
                    val stop = PiProcessStop(delayed, dispatchers, timeoutMillis = PROBE_TIMEOUT)
                    val waiting = async { stop.awaitStopped() }
                    delayed.requested.await()
                    assertTrue(process.isAlive)
                    assertFalse(waiting.isCompleted)
                    process.destroyForcibly()
                    assertTrue(waiting.await())
                    assertFalse(process.isAlive)
                }
            }
        } finally {
            process.destroyForcibly()
            withContext(Dispatchers.IO) { withTimeout(PROBE_TIMEOUT) { process.onExit().await() } }
            Files.deleteIfExists(source)
            Files.deleteIfExists(directory)
        }
    }

    private companion object {
        const val PROBE_TIMEOUT = 30_000L
    }
}

/** The test holds the stop signal, while exit observation belongs to an actual JVM child. */
private class DelayedNativeStop(private val process: Process) : Process() {
    val requested = CompletableDeferred<Unit>()
    override fun getInputStream(): InputStream = process.inputStream
    override fun getOutputStream(): OutputStream = process.outputStream
    override fun getErrorStream(): InputStream = process.errorStream
    override fun waitFor(): Int = process.waitFor()
    override fun exitValue(): Int = process.exitValue()
    override fun isAlive(): Boolean = process.isAlive
    override fun onExit(): CompletableFuture<Process> = process.onExit().thenApply { this }
    override fun descendants(): Stream<ProcessHandle> = process.descendants()
    override fun toHandle(): ProcessHandle = PiTestProcessHandle(
        { process.isAlive },
        { requested.complete(Unit) },
        process.onExit(),
    )
    override fun destroy() {
        requested.complete(Unit)
    }
    override fun destroyForcibly(): Process = apply { destroy() }
}
