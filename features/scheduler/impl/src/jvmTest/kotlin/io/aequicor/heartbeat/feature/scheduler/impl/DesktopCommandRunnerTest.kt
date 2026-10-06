package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.impl.data.DesktopCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DesktopCommandRunnerTest {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val runner = DesktopCommandRunner(TestDispatchers(Dispatchers.IO))
    private val directory = Files.createTempDirectory("scheduler-command").toString()

    @AfterTest
    fun cleanup() {
        Path.of(directory).toFile().deleteRecursively()
    }

    @Test
    fun `failed durable identity write never executes the command`() = runTest {
        withContext(Dispatchers.Default) {
            assertFailsWith<IllegalStateException> {
                runner.runTracked(directory, "echo unsafe > launched", 10.seconds) { process ->
                    assertTrue(process.group != null || process.isJobContained)
                    error("storage write failed")
                }
            }
            assertFalse(Files.exists(Path.of(directory, "launched")))
        }
    }

    @Test
    fun `a command reports its exit code and output`() = runTest {
        val outcome = withContext(Dispatchers.Default) { runner.run(directory, "echo scheduled", 30.seconds) }
        assertEquals(0, outcome.exitCode)
        assertTrue("scheduled" in outcome.output, outcome.output)
    }

    @Test
    fun `a command past its time limit is killed`() = runTest {
        val sleep = if (isWindows) "Start-Sleep -Seconds 30" else "sleep 30"
        val outcome = withContext(Dispatchers.Default) { runner.run(directory, sleep, 300.milliseconds) }
        assertNull(outcome.exitCode)
    }

    @Test
    fun `a normally exited shell cannot leave its observed child running`() = runTest {
        if (isWindows) return@runTest
        // The foreground command keeps the shell alive while the runner observes its background child.
        val outcome = withContext(Dispatchers.Default) {
            runner.run(directory, "sleep 30 & echo \$!; sleep 1", 10.seconds)
        }
        val child = ProcessHandle.of(outcome.output.trim().toLong()).orElse(null)
        try {
            assertEquals(0, outcome.exitCode)
            assertFalse(child?.isAlive == true, "The background child survived its shell")
        } finally {
            child?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }

    @Test
    fun `cancelling a command with continuous output kills its process`() = runTest {
        if (isWindows) return@runTest
        withContext(Dispatchers.Default) {
            val path = Path.of(directory)
            val marker = path.resolve("pid")
            path.fileSystem.newWatchService().use { watcher ->
                path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
                val running = async {
                    runner.run(directory, "echo \$\$ > pid.tmp; mv pid.tmp pid; exec yes streaming", 30.seconds)
                }
                var process: ProcessHandle? = null
                try {
                    // An atomic rename signals a real process start without timing-based test synchronization.
                    withTimeout(5.seconds) {
                        while (!Files.exists(marker)) {
                            runInterruptible(Dispatchers.IO) { watcher.take().reset() }
                        }
                    }
                    process = ProcessHandle.of(Files.readString(marker).trim().toLong()).orElse(null)
                    running.cancel()
                    withTimeout(5.seconds) { running.join() }
                    assertFalse(process?.isAlive == true, "Cancelled streaming process survived")
                } finally {
                    running.cancel()
                    process?.takeIf { it.isAlive }?.destroyForcibly()
                }
            }
        }
    }
}
