package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class CommandRunnerCancellationTest {
    @Test
    fun `cancellation returns only after the tool process has stopped`() = runTest {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return@runTest
        val root = createTempDirectory("app-update-command")
        // The shell waits on its open stdin without spawning a child process.
        val script = Files.writeString(root.resolve("waiting.sh"), "#!/bin/sh\nread line\n")
        var child: ProcessHandle? = null
        lateinit var running: Job
        val ioDispatcher = BeforeWaitDispatcher {
            child = ProcessHandle.current().children().use { processes ->
                processes.filter { it.info().commandLine().orElse("").contains("$script") }.findFirst().orElse(null)
            }
            assertNotNull(child, "The tool must have started before cancellation")
            running.cancel()
        }
        val dispatchers = object : DispatcherProvider by TestDispatchers {
            override val io: CoroutineDispatcher = ioDispatcher
        }
        try {
            running = launch(start = CoroutineStart.LAZY) {
                CommandRunner(dispatchers).run(listOf("/bin/sh", "$script"))
            }
            running.start()
            running.join()

            assertTrue(running.isCancelled)
            assertFalse(assertNotNull(child).isAlive, "The caller must be able to clean up without a running tool")
        } finally {
            child?.takeIf { it.isAlive }?.let { process ->
                process.destroyForcibly()
                process.onExit().get()
            }
            root.deleteRecursively()
        }
    }

    /** Cancels immediately after process creation and before its interruptible wait starts. */
    private class BeforeWaitDispatcher(private val cancel: () -> Unit) : CoroutineDispatcher() {
        private var isFirst = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (isFirst) {
                isFirst = false
                cancel()
            }
            block.run()
        }
    }
}
