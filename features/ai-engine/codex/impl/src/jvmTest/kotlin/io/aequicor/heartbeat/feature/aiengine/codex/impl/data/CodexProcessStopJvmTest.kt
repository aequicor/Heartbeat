package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodexProcessStopJvmTest {
    @Test
    fun `stored identity stops a real JVM child only after the journal write completes`() = runTest {
        val directory = Files.createTempDirectory("codex-stop-probe")
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
                    val dispatchers = object : DispatcherProvider {
                        override val main = Dispatchers.Default
                        override val default = Dispatchers.Default
                        override val io = Dispatchers.IO
                    }
                    val owner = assertNotNull(CodexProcessOwnership(process::toHandle, dispatchers).capture())
                    val persisting = CompletableDeferred<Unit>()
                    val persisted = CompletableDeferred<Unit>()
                    val waiting = async {
                        CodexProcessStop(dispatchers).stop(owner, { true }) {
                            persisting.complete(Unit)
                            persisted.await()
                            it
                        }
                    }
                    persisting.await()
                    assertTrue(process.isAlive)
                    assertFalse(waiting.isCompleted)
                    persisted.complete(Unit)
                    assertTrue(waiting.await())
                    assertFalse(process.isAlive)
                    assertTrue(CodexProcessStop(dispatchers).stop(owner, { true }) { it })
                }
            }
        } finally {
            process.toHandle().destroyForcibly()
            withContext(Dispatchers.IO) { withTimeout(PROBE_TIMEOUT) { process.onExit().await() } }
            Files.deleteIfExists(source)
            Files.deleteIfExists(directory)
        }
    }

    private companion object {
        const val PROBE_TIMEOUT = 30_000L
    }
}
