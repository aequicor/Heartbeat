package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpCommand
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
            val waiting = backgroundScope.async { assertFailsWith<Exception> { transport.receive() } }
            testScheduler.runCurrent()
            transport.close()
            waiting.await()
        }
    }

    private suspend fun withAgent(block: suspend (AcpTransport) -> Unit) {
        val directory = Files.createTempDirectory("acp fixture ")
        val source = directory.resolve("Agent.java")
        Files.writeString(
            source,
            """
            import java.io.*;
            import java.nio.charset.StandardCharsets;
            class Agent {
                public static void main(String[] args) throws Exception {
                    System.err.println("private diagnostic");
                    var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    var writer = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
                    writer.println(ProcessHandle.current().pid());
                    writer.println(reader.readLine());
                }
            }
            """.trimIndent(),
        )
        val dispatchers = object : DispatcherProvider {
            override val main = Dispatchers.Default
            override val default = Dispatchers.Default
            override val io = Dispatchers.IO
        }
        val factory = JvmAcpStdioTransportFactory(dispatchers)
        assertTrue(factory.isSupported)
        try {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val transport = factory.open(AcpCommand(java, listOf(source.toString())))
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
