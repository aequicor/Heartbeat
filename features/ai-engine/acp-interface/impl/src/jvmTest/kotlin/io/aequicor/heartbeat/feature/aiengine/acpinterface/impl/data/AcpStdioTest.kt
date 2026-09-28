package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpCommand
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
                assertFailsWith<AcpException.Disconnected> { transport.receive() }
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
}
