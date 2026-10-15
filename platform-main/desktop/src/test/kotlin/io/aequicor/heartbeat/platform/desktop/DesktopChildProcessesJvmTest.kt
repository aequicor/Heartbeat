package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DesktopChildProcessesJvmTest {
    @Test
    fun `a real JVM child is stopped after its captured parent has exited`() = runTest(timeout = 30.seconds) {
        withContext(Dispatchers.IO) {
            val directory = Files.createTempDirectory("heartbeat process cleanup ")
            val source = directory.resolve("DesktopProcessFixture.java")
            Files.writeString(source, fixture)
            val java = Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            ).toString()
            val parent = ProcessBuilder(java, source.toString(), java, source.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            var child: ProcessHandle? = null
            try {
                val ready = CompletableFuture.supplyAsync { parent.inputStream.bufferedReader().readLine() }
                child = ProcessHandle.of(ready.get(15, TimeUnit.SECONDS).toLong()).orElseThrow()
                val cleanup = DesktopChildProcesses(dispatchers, parent.toHandle(), timeoutMillis = 10_000)
                cleanup.capture()
                assertTrue(child.isAlive)

                parent.outputStream.close()
                assertTrue(parent.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, parent.exitValue())
                assertFalse(parent.isAlive)
                assertTrue(child.isAlive)

                cleanup.stop()

                assertFalse(parent.isAlive)
                assertFalse(child.isAlive)
            } finally {
                stopFixture(parent, child)
                Files.deleteIfExists(source)
                Files.deleteIfExists(directory)
            }
        }
    }

    private fun stopFixture(parent: Process, child: ProcessHandle?) {
        // Limit fallback cleanup to this fixture, including failures before its readiness line arrives.
        val liveChildren = if (parent.isAlive) parent.descendants().use { it.toList() } else emptyList()
        val leftovers = (liveChildren + listOfNotNull(child) + parent.toHandle()).distinct()
        leftovers.forEach { if (it.isAlive) it.destroyForcibly() }
        leftovers.forEach { if (it.isAlive) it.onExit().get(5, TimeUnit.SECONDS) }
    }

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Default
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    private val fixture = """
        import java.io.*;
        import java.util.concurrent.*;
        class DesktopProcessFixture {
            public static void main(String[] args) throws Exception {
                if (args.length == 1) {
                    new PrintWriter(System.out, true).println(ProcessHandle.current().pid());
                    new CountDownLatch(1).await(1, TimeUnit.MINUTES);
                    return;
                }
                var child = new ProcessBuilder(args[0], args[1], "child")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                var ready = new BufferedReader(new InputStreamReader(child.getInputStream())).readLine();
                new PrintWriter(System.out, true).println(ready);
                System.in.read();
            }
        }
    """.trimIndent()
}
