package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpCommand
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AcpStdioTest {
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Default
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }
    private val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    @Test
    fun `stdio preserves unicode and separates stderr`() = runTest(timeout = 30.seconds) {
        withAgent { transport ->
            val frame = """{"jsonrpc":"2.0","method":"echo","params":{"text":"Привет\n世界"}}"""
            transport.send(frame)
            assertEquals(frame, transport.receive())
            assertNull(transport.receive())
        }
    }

    @Test
    fun `close kills agent blocked on stdin and unblocks receive`() = runTest(timeout = 30.seconds) {
        withAgent { transport ->
            val waiting = backgroundScope.async {
                // Closing can race the reader's EOF; both outcomes mean disconnection by contract.
                val result = try {
                    Result.success(transport.receive())
                } catch (error: AcpException.Disconnected) {
                    Result.failure(error)
                }
                if (result.isSuccess) {
                    assertNull(result.getOrThrow())
                } else {
                    assertIs<AcpException.Disconnected>(result.exceptionOrNull())
                }
            }
            testScheduler.runCurrent()
            transport.close()
            waiting.await()
        }
    }

    @Test
    fun `close kills descendants that inherit the agent pipes`() = runTest(timeout = 30.seconds) {
        withAgent(isSpawningChild = true) { transport ->
            val child = ProcessHandle.of(requireNotNull(transport.receive()).toLong()).orElseThrow()
            try {
                transport.close()
                withContext(Dispatchers.IO) { child.onExit().get(5, TimeUnit.SECONDS) }
                assertFalse(child.isAlive)
            } finally {
                child.destroyForcibly()
            }
        }
    }

    @Test
    fun `close during an in-flight write closes stdin once the write finishes`() = runTest(timeout = 30.seconds) {
        val stdin = BlockingStdin()
        val transport = JvmAcpTransport(FakeProcess(stdin), dispatchers)
        val writing = backgroundScope.async(Dispatchers.IO) { transport.send("{}") }
        withContext(Dispatchers.IO) { assertTrue(stdin.entered.await(5, TimeUnit.SECONDS)) }
        transport.close()
        assertFalse(stdin.isClosed)
        stdin.release.countDown()
        writing.await()
        assertTrue(stdin.isClosed)
    }

    @Test
    fun `missing executable fails with LaunchFailed without the platform cause`() = runTest(timeout = 30.seconds) {
        val missing = Path.of(System.getProperty("java.io.tmpdir"), "missing-acp-agent").toString()
        val error = assertFailsWith<AcpException.LaunchFailed> {
            JvmAcpStdioTransportFactory(dispatchers).open(AcpCommand(missing))
        }
        // The IOException names the executable; it must not travel in the cause chain.
        // Coroutine stack-trace recovery may add a copy of LaunchFailed itself as the cause.
        assertTrue(generateSequence<Throwable>(error) { it.cause }.all { it is AcpException.LaunchFailed })
    }

    private suspend fun withAgent(isSpawningChild: Boolean = false, block: suspend (AcpTransport) -> Unit) {
        val directory = Files.createTempDirectory("acp fixture ")
        val source = directory.resolve("Agent.java")
        Files.writeString(
            source,
            """
            import java.io.*;
            import java.nio.charset.StandardCharsets;
            class Agent {
                public static void main(String[] args) throws Exception {
                    if (args.length > 0 && args[0].equals("child")) {
                        Thread.sleep(600_000);
                        return;
                    }
                    // The agent's stderr must never reach the ACP frame stream.
                    OutputStream diagnostics = System.err;
                    diagnostics.write("private diagnostic\n".getBytes(StandardCharsets.UTF_8));
                    var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    var writer = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
                    writer.println(ProcessHandle.current().pid());
                    if (args.length > 0 && args[0].equals("spawn")) {
                        var child = new ProcessBuilder(args[1], args[2], "child").inheritIO().start();
                        writer.println(child.pid());
                    }
                    writer.println(reader.readLine());
                }
            }
            """.trimIndent(),
        )
        val factory = JvmAcpStdioTransportFactory(dispatchers)
        assertTrue(factory.isSupported)
        val arguments = listOf(source.toString()) +
            if (isSpawningChild) listOf("spawn", java, source.toString()) else emptyList()
        try {
            val transport = factory.open(AcpCommand(java, arguments))
            try {
                val pid = requireNotNull(transport.receive()).toLong()
                val process = ProcessHandle.of(pid).orElseThrow()
                try {
                    block(transport)
                } finally {
                    transport.close()
                    withContext(Dispatchers.IO) { process.onExit().get(5, TimeUnit.SECONDS) }
                    assertFalse(process.isAlive)
                }
            } finally {
                transport.close()
            }
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(directory)
        }
    }

    private class BlockingStdin : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile
        var isClosed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }

        override fun close() {
            isClosed = true
        }
    }

    private class FakeProcess(private val stdin: OutputStream) : Process() {
        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }
}
