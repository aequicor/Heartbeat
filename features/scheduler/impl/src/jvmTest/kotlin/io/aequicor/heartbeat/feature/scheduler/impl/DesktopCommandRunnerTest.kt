package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.api.TaskProcess
import io.aequicor.heartbeat.feature.scheduler.impl.data.DesktopCommandRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `an observed child which changes group dies after its parent exits`() = runTest {
        escapedDescendant { snapshot, child ->
            assertTrue(snapshot.descendants.any { it.pid == child.pid() })
            Files.writeString(Path.of(directory, "exit-parent"), "exit")
        }
    }

    @Test
    fun `recovery tracks escaped descendants even when their original group is gone`() = runTest {
        escapedDescendant { snapshot, child ->
            // Reproduce the persisted identity after the original group has exited, retaining the escaped child.
            val recovered = Json.decodeFromString<TaskProcess>(Json.encodeToString(snapshot)).copy(
                group = UNUSED_GROUP,
                descendants = snapshot.descendants.filter { it.pid == child.pid() },
            )
            assertFalse(runner.isStopped(recovered))
            assertFalse(runner.isStopped(snapshot.copy(startedAt = "reused leader")))
            val reused = recovered.copy(descendants = recovered.descendants.map { it.copy(startedAt = "other") })
            assertTrue(runner.isStopped(reused))
            assertTrue(runner.stop(reused))
            assertTrue(child.isAlive, "A different start time must not authorize killing a reused PID")
            assertTrue(runner.stop(recovered))
            assertFalse(child.isAlive)
            Files.writeString(Path.of(directory, "exit-parent"), "exit")
        }
    }

    @Test
    fun `timeout kills a child which left its original group`() = runTest {
        escapedDescendant(Completion.TimedOut) { _, _ -> }
    }

    @Test
    fun `cancellation kills a child which left its original group`() = runTest {
        escapedDescendant(Completion.Cancelled) { _, _ -> }
    }

    private suspend fun escapedDescendant(
        completion: Completion = Completion.Succeeded,
        observed: suspend (TaskProcess, ProcessHandle) -> Unit,
    ) {
        if (isWindows) return
        withContext(Dispatchers.Default) {
            val command = PosixDescendantTestProcess.arguments("parent", directory)
                .joinToString(" ") { "'${it.replace("'", "'\"'\"'")}'" }
            val path = Path.of(directory)
            val marker = path.resolve("escaped-pid")
            path.fileSystem.newWatchService().use { watcher ->
                path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
                val observation = EscapedObservation(marker, watcher, observed)
                val running = async {
                    runner.runTracked(directory, command, 5.seconds, observation::accept)
                }
                try {
                    if (completion == Completion.Cancelled) {
                        withTimeout(15.seconds) { observation.retained.await() }
                        running.cancelAndJoin()
                    } else {
                        val outcome = running.await()
                        assertTrue(
                            observation.retained.isCompleted,
                            "The escaped child was not retained: ${outcome.output}",
                        )
                        assertEquals(if (completion == Completion.TimedOut) null else 0, outcome.exitCode)
                    }
                    assertFalse(observation.child?.isAlive == true, "The escaped child survived its command")
                } finally {
                    running.cancelAndJoin()
                    if (Files.exists(marker)) {
                        ProcessHandle.of(Files.readString(marker).trim().toLong()).orElse(null)
                            ?.takeIf { it.isAlive }?.destroyForcibly()
                    }
                }
            }
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

    private companion object {
        const val UNUSED_GROUP = 999_999_999L
    }

    private enum class Completion { Succeeded, TimedOut, Cancelled }
}

private class EscapedObservation(
    private val marker: Path,
    private val watcher: WatchService,
    private val observed: suspend (TaskProcess, ProcessHandle) -> Unit,
) {
    val retained = CompletableDeferred<Unit>()
    var child: ProcessHandle? = null
        private set

    suspend fun accept(snapshot: TaskProcess) {
        if (retained.isCompleted || snapshot.descendants.isEmpty()) return
        while (!Files.exists(marker)) runInterruptible(Dispatchers.IO) { watcher.take().reset() }
        val pid = Files.readString(marker).trim().toLong()
        if (snapshot.descendants.none { it.pid == pid }) return
        child = ProcessHandle.of(pid).orElseThrow()
        observed(snapshot, assertNotNull(child))
        retained.complete(Unit)
    }
}
