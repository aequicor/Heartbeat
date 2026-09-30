package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingShellToolTest {
    private val project = Files.createTempDirectory("coding-shell")
    private val dispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val tool = CodingShellTool(ProjectRoot(project), dispatcher)

    @AfterTest
    fun cleanUp() {
        dispatcher.close()
        project.toFile().deleteRecursively()
    }

    @Test
    fun commandRunsInTheProjectRootAndReportsExitCode() = runTest {
        project.resolve("marker.txt").writeText("x")
        val listing = tool.run(args("ls"))
        assertFalse(listing.isError)
        assertTrue("marker.txt" in listing.text)
        assertTrue(listing.text.startsWith("Exit code: 0"))
        assertTrue(tool.run(args("exit 3")).isError)
    }

    @Test
    fun commandWithAnUnreviewableSuffixNeverStarts() = runTest {
        val suffix = if (isWindows) "; Set-Content hidden.txt changed" else "; touch hidden.txt"
        val result = tool.run(args("echo safe" + " ".repeat(MAX_CODING_COMMAND_CHARS) + suffix))
        assertTrue(result.isError)
        assertTrue(result.text.startsWith("Refused"))
        assertFalse(Files.exists(project.resolve("hidden.txt")))
    }

    @Test
    fun commandIsKilledAfterTimeout() = runTest {
        val started = System.nanoTime()
        val sleep = tool.run(args(if (isWindows) "Start-Sleep -Seconds 30" else "sleep 30", timeout = 1))
        assertTrue(sleep.isError)
        assertTrue(sleep.text.startsWith("Timed out"))
        assertTrue(elapsedSeconds(started) < QUICK_SECONDS)
    }

    @Test
    fun backgroundChildHoldingTheOutputDoesNotHangTheCommand() = runTest {
        val started = System.nanoTime()
        val detached = if (isWindows) {
            "cmd /c \"start /b ping -n 30 127.0.0.1 >nul\"; 'done'"
        } else {
            "sleep 30 & echo done"
        }
        val result = tool.run(args(detached, timeout = 60))
        assertTrue("done" in result.text, result.text)
        assertTrue(elapsedSeconds(started) < QUICK_SECONDS)
    }

    @Test
    fun outputAfterExitIsReadForBoundedTimeOnly() {
        val ms = 1_000_000L
        assertEquals(null, drainOutcome(isAlive = true, read = 10, sinceExitNanos = 0, isPastDeadline = false))
        assertEquals(false, drainOutcome(isAlive = true, read = 10, sinceExitNanos = 0, isPastDeadline = true))
        assertEquals(true, drainOutcome(isAlive = true, read = -1, sinceExitNanos = 0, isPastDeadline = false))
        // A detached child writing without pauses cannot keep the command running past the limit.
        assertEquals(null, drainOutcome(isAlive = false, read = 10, sinceExitNanos = 500 * ms, isPastDeadline = false))
        assertEquals(true, drainOutcome(isAlive = false, read = 10, sinceExitNanos = 2_001 * ms, isPastDeadline = true))
        assertEquals(null, drainOutcome(isAlive = false, read = 0, sinceExitNanos = 100 * ms, isPastDeadline = false))
        assertEquals(true, drainOutcome(isAlive = false, read = 0, sinceExitNanos = 201 * ms, isPastDeadline = false))
    }

    @Test
    fun longOutputKeepsHeadAndTail() = runTest {
        val loud = if (isWindows) {
            "'first'; 'x' * 40000; 'last'"
        } else {
            "echo first; head -c 40000 /dev/zero | tr '\\0' x; echo; echo last"
        }
        val result = tool.run(args(loud))
        assertTrue(result.text.substringAfter("Exit code: 0").trimStart().startsWith("first"), result.text.take(200))
        assertTrue(result.text.trimEnd().endsWith("last"))
        assertTrue("bytes omitted" in result.text)
    }

    @Test
    fun commandIsMutatingAndShowsItsFullText() {
        assertTrue(tool.isMutating)
        assertTrue(tool.target(args("git status")) == "git status")
    }

    private fun elapsedSeconds(started: Long) = (System.nanoTime() - started) / NANOS_PER_SECOND

    private fun args(command: String, timeout: Int? = null) = JsonObject(
        buildMap {
            put("command", JsonPrimitive(command))
            timeout?.let { put("timeout_seconds", JsonPrimitive(it)) }
        },
    )

    private companion object {
        const val QUICK_SECONDS = 15
        const val NANOS_PER_SECOND = 1_000_000_000L
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    }
}
