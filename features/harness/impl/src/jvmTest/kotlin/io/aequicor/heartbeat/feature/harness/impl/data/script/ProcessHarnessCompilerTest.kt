package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HARNESS_COMPILER_WORKER_READY
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HarnessCompilerLauncher
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.JavaHarnessCompilerLauncher
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.WORKER_OUT_OF_MEMORY_EXIT
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Real worker processes: their budgets run in real time, so the timers are real too. */
class ProcessHarnessCompilerTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val directory = Files.createTempDirectory("harness-worker-test")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        directory.toFile().deleteRecursively()
    }

    private fun compiler(launcher: HarnessCompilerLauncher, budget: Duration = COMPILER_RUN_BUDGET) =
        ProcessHarnessCompiler(scope, Dispatchers.IO, Dispatchers.Default, launcher, budget)

    @Test
    fun `one reused worker compiles code and reports compile errors as diagnostics`() = runTest(timeout = 3.minutes) {
        val workers = RecordingLauncher(JavaHarnessCompilerLauncher())
        val compiler = compiler(workers)
        val jar = Files.createTempFile(directory, "compiler-", ".jar")
        assertIs<HarnessCompilerOutcome.Compiled>(
            compiler.compile(HarnessCodeKind.Workflow, "workflow { input -> input }", jar),
        )
        assertTrue(Files.size(jar) > 0)
        val unused = directory.resolve("unused.jar")
        val failed = assertIs<HarnessCompilerOutcome.Failed>(
            compiler.compile(HarnessCodeKind.Script, "val broken = probe_unresolved", unused),
        )
        assertTrue(failed.diagnostics.any { it.severity == HarnessDiagnosticSeverity.Error })
        assertFalse(Files.exists(unused))
        val worker = workers.processes.single()
        assertTrue(worker.isAlive)
        scope.cancel()
        worker.onExit().get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `compilation past its budget returns only after its worker died and never accumulates workers`() = runTest {
        val workers = RecordingLauncher(helper())
        val compiler = compiler(workers, budget = 1.seconds)
        repeat(2) {
            assertEquals(HarnessCompilerOutcome.TimedOut, compiler.compile(HarnessCodeKind.Script, "", output()))
            assertFalse(workers.processes.last().isAlive)
        }
        assertEquals(2, workers.processes.size)
    }

    @Test
    fun `cancelled compilation kills its worker before it completes`() = runTest {
        val started = CompletableDeferred<ProcessHandle>()
        val hung = helper()
        val compiler = compiler({ hung.start().also { started.complete(it.toHandle()) } })
        val compiling = launch(Dispatchers.Default) { compiler.compile(HarnessCodeKind.Script, "", output()) }
        val worker = started.await()
        compiling.cancelAndJoin()
        assertFalse(worker.isAlive)
    }

    @Test
    fun `worker that dies during a compilation makes it unavailable`() = runTest {
        val workers = RecordingLauncher(helper("exit"))
        val compiler = compiler(workers)
        assertEquals(HarnessCompilerOutcome.Unavailable, compiler.compile(HarnessCodeKind.Script, "", output()))
        assertFalse(workers.processes.single().isAlive)
    }

    @Test
    fun `worker that runs out of memory reports the diagnostic even when its exit lags behind its streams`() = runTest {
        val workers = RecordingLauncher(helper("oom"))
        val compiler = compiler(workers)
        val failed = assertIs<HarnessCompilerOutcome.Failed>(
            compiler.compile(HarnessCodeKind.Script, "", output()),
        )
        assertTrue(failed.diagnostics.single().message.contains("out of memory"))
        assertFalse(workers.processes.single().isAlive)
    }

    @Test
    fun `worker classpath passed through an argument file starts the real worker`() {
        val worker = JavaHarnessCompilerLauncher(isClasspathInFile = true).start()
        try {
            // Not every platform reports a child's arguments; where it does, no classpath is on the command line.
            worker.info().arguments().ifPresent { arguments ->
                assertTrue(arguments.any { it.startsWith("@") })
                assertFalse("-cp" in arguments)
            }
            assertEquals(HARNESS_COMPILER_WORKER_READY, DataInputStream(worker.inputStream).readInt())
        } finally {
            worker.destroyForcibly()
            worker.onExit().get(10, TimeUnit.SECONDS)
        }
    }

    private fun output(): Path = directory.resolve("output.jar")

    /** A stand-in worker: it announces itself and then never answers, or exits at once with "exit". */
    private fun helper(vararg arguments: String) = HarnessCompilerLauncher {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        ProcessBuilder(listOf(java, "-cp", classpath, HelperCompilerWorker::class.java.name) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}

/** Records the workers it starts and checks that each earlier one has died first. */
private class RecordingLauncher(private val launcher: HarnessCompilerLauncher) : HarnessCompilerLauncher {
    private val started = CopyOnWriteArrayList<ProcessHandle>()
    val processes: List<ProcessHandle> get() = started.toList()

    override fun start(): Process {
        check(started.none { it.isAlive }) { "an earlier worker still runs" }
        return launcher.start().also { started += it.toHandle() }
    }
}

internal object HelperCompilerWorker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.firstOrNull() == "exit") exitProcess(EXIT_CODE)
        if (arguments.firstOrNull() == "oom") {
            // Like HotSpot's ExitOnOutOfMemoryError: the streams close first, the exit code follows a moment later.
            FileOutputStream(FileDescriptor.out).close()
            Thread.sleep(EXIT_LAG_MILLIS)
            exitProcess(WORKER_OUT_OF_MEMORY_EXIT)
        }
        // Never outlives the test JVM, even when a test fails before killing it.
        ProcessHandle.current().parent().ifPresent { it.onExit().thenRun { Runtime.getRuntime().halt(0) } }
        DataOutputStream(FileOutputStream(FileDescriptor.out)).apply {
            writeInt(HARNESS_COMPILER_WORKER_READY)
            flush()
        }
        CountDownLatch(1).await()
    }

    private const val EXIT_CODE = 7
    private const val EXIT_LAG_MILLIS = 300L
}
