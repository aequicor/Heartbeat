package io.aequicor.heartbeat.feature.plantumlsupport.impl

import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlStyle
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.ProcessPlantUmlEngine
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.JavaPlantUmlWorkerLauncher
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerRequest
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.WORKER_JVM_OPTIONS
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.workerEnvironment
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.writeRequest
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlLimits
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlSource
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.plantUmlPreamble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Real worker processes: hostile sources end the worker, never the test JVM, and the next drawing gets a new one. */
class ProcessPlantUmlEngineTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val limits = PlantUmlLimits(timeout = 30.seconds)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun engine(idleTimeout: Duration = 1.minutes) = ProcessPlantUmlEngine(
        scope = scope,
        timers = Dispatchers.Default,
        // A heap the memory bomb exhausts in well under a second.
        launcher = JavaPlantUmlWorkerLauncher(WORKER_JVM_OPTIONS.map { if (it.startsWith("-Xmx")) "-Xmx128m" else it }),
        idleTimeout = idleTimeout,
    )

    private fun ProcessPlantUmlEngine.draw(
        text: String,
        limits: PlantUmlLimits = this@ProcessPlantUmlEngineTest.limits,
    ) = render(PlantUmlSource.parse(text) ?: error("unsupported sample"), plantUmlPreamble(Style, 1f), limits)

    @Test
    fun `diagrams are drawn by one reused worker`() {
        val engine = engine()
        assertIs<PlantUmlResult.Image>(engine.draw(DIAGRAM))
        val worker = assertNotNull(engine.process)
        assertIs<PlantUmlResult.SyntaxError>(engine.draw("@startuml\nA -> B\nthis is -> not -> valid ]]\n@enduml"))
        assertEquals(worker.pid(), engine.process?.pid())
    }

    @Test
    fun `a source that exhausts memory ends only its worker`() {
        val engine = engine()
        val bomb = "@startuml\n!\$a = \"xxxxxxxxxx\"\n" + "!\$a = \$a + \$a\n".repeat(40) + "A -> B : \$a\n@enduml"
        assertEquals(PlantUmlResult.Failed(PlantUmlFailure.TooLarge), engine.draw(bomb))
        assertIs<PlantUmlResult.Image>(engine.draw(DIAGRAM))
    }

    @Test
    fun `a drawing past its time limit is ended by killing its worker`() {
        val engine = engine()
        assertIs<PlantUmlResult.Image>(engine.draw(DIAGRAM))
        val first = assertNotNull(engine.process)
        val started = TimeSource.Monotonic.markNow()
        val result = engine.draw(BACKTRACKING, limits.copy(timeout = 2.seconds))
        assertEquals(PlantUmlResult.Failed(PlantUmlFailure.Timeout), result)
        assertTrue(started.elapsedNow() < 15.seconds, "ended at the limit")
        assertTrue(first.onExit().get(5, TimeUnit.SECONDS) != null && !first.isAlive)
        assertIs<PlantUmlResult.Image>(engine.draw(DIAGRAM))
        assertNotEquals(first.pid(), engine.process?.pid())
    }

    @Test
    fun `the worker stops when idle and when its scope ends`() {
        val idle = engine(idleTimeout = 300.milliseconds)
        assertIs<PlantUmlResult.Image>(idle.draw(DIAGRAM))
        val idleWorker = assertNotNull(idle.process)
        idleWorker.onExit().get(10, TimeUnit.SECONDS)
        assertTrue(!idleWorker.isAlive)

        val engine = engine()
        assertIs<PlantUmlResult.Image>(engine.draw(DIAGRAM))
        val worker = assertNotNull(engine.process)
        scope.cancel()
        worker.onExit().get(10, TimeUnit.SECONDS)
        assertTrue(!worker.isAlive)
        assertEquals(PlantUmlResult.Failed(PlantUmlFailure.Internal), engine.draw(DIAGRAM), "no worker after the scope")
    }

    @Test
    fun `cancelling the scope ends a hung drawing at once`() {
        val engine = engine()
        val result = CompletableFuture.supplyAsync { engine.draw(BACKTRACKING) }
        val worker = awaitWorker(engine)
        scope.cancel()
        assertEquals(PlantUmlResult.Failed(PlantUmlFailure.Internal), result.get(10, TimeUnit.SECONDS))
        worker.onExit().get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `a busy worker ends with the app process`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val app = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), HungAppMain::class.java.name)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            val pid = app.inputStream.bufferedReader().readLine().toLong()
            val worker = assertNotNull(ProcessHandle.of(pid).orElse(null))
            Thread.sleep(500)
            assertTrue(worker.isAlive, "the worker is drawing")
            app.destroyForcibly()
            worker.onExit().get(10, TimeUnit.SECONDS)
        } finally {
            app.destroyForcibly()
        }
    }

    private fun awaitWorker(engine: ProcessPlantUmlEngine): ProcessHandle {
        repeat(100) {
            engine.process?.let { return it }
            Thread.sleep(50)
        }
        error("no worker started")
    }

    @Test
    fun `the worker gets no secrets from the environment`() {
        val parent = mapOf(
            "TMPDIR" to "/tmp",
            "OPENAI_API_KEY" to "secret",
            "SystemRoot" to "C:\\Windows",
            "HOME" to "/u",
        )
        assertEquals(mapOf("TMPDIR" to "/tmp", "SystemRoot" to "C:\\Windows"), workerEnvironment(parent))
    }

    private companion object {
        const val DIAGRAM = "@startuml\nA -> B : hello\n@enduml"
        val Style = PlantUmlStyle(0xFF000000.toInt(), 0, 0, 0, 0, 0, fontSize = 14f, isDark = false)
    }
}

/** Catastrophic backtracking: each further character doubles the time. */
private const val BACKTRACKING = "@startuml\n!\$p = %splitstr_regex(\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\", " +
    "\"((x+)\\2?)+y\")\nA -> B : \$p\n@enduml"

/** An "app" that starts a worker on a drawing that never ends, prints the worker's pid and waits to be killed. */
internal object HungAppMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val worker = JavaPlantUmlWorkerLauncher().start()
        val output = DataOutputStream(worker.outputStream)
        val source = PlantUmlSource.parse(BACKTRACKING) ?: error("unsupported sample")
        output.writeRequest(PlantUmlWorkerRequest(source, emptyList(), PlantUmlLimits()))
        FileOutputStream(FileDescriptor.out).write("${worker.pid()}\n".encodeToByteArray())
        Thread.sleep(Long.MAX_VALUE)
    }
}
