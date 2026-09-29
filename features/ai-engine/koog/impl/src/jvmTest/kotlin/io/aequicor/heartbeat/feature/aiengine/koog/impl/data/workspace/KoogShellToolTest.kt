package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoogShellToolTest {
    private val project = Files.createTempDirectory("koog-shell")
    private val tool = KoogShellTool(ProjectRoot(project), Dispatchers.IO)

    @AfterTest
    fun cleanUp() {
        project.toFile().deleteRecursively()
    }

    @Test
    fun commandRunsInTheProjectRootAndReportsExitCode() = runTest {
        project.resolve("marker.txt").writeText("x")
        val listing = tool.run(args("ls"))
        assertFalse(listing.isFailed)
        assertTrue("marker.txt" in listing.text)
        assertTrue(listing.text.startsWith("Exit code: 0"))
        assertTrue(tool.run(args("exit 3")).isFailed)
    }

    @Test
    fun commandIsKilledAfterTimeout() = runTest {
        val sleep = tool.run(args("Start-Sleep -Seconds 30; sleep 30", timeout = 1))
        assertTrue(sleep.isFailed)
        assertTrue(sleep.text.startsWith("Timed out"))
    }

    @Test
    fun commandIsMutatingAndShowsItsFullText() {
        assertTrue(tool.isMutating)
        assertTrue(tool.target(args("git status")) == "git status")
    }

    private fun args(command: String, timeout: Int? = null) = JsonObject(
        buildMap {
            put("command", JsonPrimitive(command))
            timeout?.let { put("timeout_seconds", JsonPrimitive(it)) }
        },
    )
}
