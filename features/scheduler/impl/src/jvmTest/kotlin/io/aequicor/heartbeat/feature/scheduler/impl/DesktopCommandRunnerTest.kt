package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.impl.data.DesktopCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DesktopCommandRunnerTest {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val runner = DesktopCommandRunner(TestDispatchers(Dispatchers.IO))
    private val directory = Files.createTempDirectory("scheduler-command").toString()

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
}
