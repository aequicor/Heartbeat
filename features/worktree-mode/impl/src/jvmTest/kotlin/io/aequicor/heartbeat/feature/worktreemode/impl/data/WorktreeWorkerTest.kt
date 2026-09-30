package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.BoundedBuildOutput
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.MAX_BUILD_OUTPUT
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorkerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorktreeWorkerTest {
    @Test
    fun `two worker JVMs serialize shared resources and cancellation releases the OS lock`() = runTest {
        withContext(RealTestDispatchers.io) {
            val root = Files.createTempDirectory("heartbeat-worker-test-").toRealPath()
            val first = execution(root, "first", isHold = true)
            val second = execution(root, "second")
            val processes = mutableListOf<Process>()
            try {
                processes += launch(first)
                await(first) { it.operation.phase == WorktreeBuildPhase.Running }
                processes += launch(second)
                await(second) { it.operation.phase == WorktreeBuildPhase.WaitingForResource }
                assertTrue(processes[1].isAlive)
                Files.writeString(Path.of(first.jobDirectory).resolve("cancel.request"), "cancel")
                assertEquals(
                    WorktreeBuildPhase.Cancelled,
                    await(first) { it.operation.phase == WorktreeBuildPhase.Cancelled }.operation.phase,
                )
                val result = await(second) { it.operation.phase in terminal }
                assertEquals(WorktreeBuildPhase.Completed, result.operation.phase)
                assertTrue(result.operation.output.contains("version"))
            } finally {
                processes.forEach {
                    stopWorker(it)
                }
                removeTestTree(root)
            }
        }
    }

    @Test
    fun `parent PID reuse or parent loss cancels before executing the command`() = runTest {
        withContext(RealTestDispatchers.io) {
            val root = Files.createTempDirectory("heartbeat-parent-test-").toRealPath()
            val execution = execution(
                root,
                "orphan",
            ).copy(parentProcess = ProcessHandle.current().pid(), parentStartedAt = "wrong-start-time")
            val process = launch(execution)
            try {
                val result = await(execution) { it.operation.phase in terminal }
                assertEquals(WorktreeBuildPhase.Cancelled, result.operation.phase)
                assertEquals("ParentLost", result.operation.failure)
            } finally {
                stopWorker(process)
                removeTestTree(root)
            }
        }
    }

    @Test
    fun `output keeps initial context and recent tail without growing unbounded`() {
        val output = BoundedBuildOutput()
        output.append("small")
        assertEquals("small", output.snapshot())
        output.append("x".repeat(MAX_BUILD_OUTPUT * 3))
        output.append("TAIL")
        val bounded = output.snapshot()
        assertTrue(bounded.length <= MAX_BUILD_OUTPUT)
        assertTrue(bounded.startsWith("small"))
        assertTrue(bounded.endsWith("TAIL"))
    }

    @Test
    fun `build timeout terminates its foreground child before publishing failure`() = runTest {
        withContext(RealTestDispatchers.io) {
            val root = Files.createTempDirectory("heartbeat-timeout-test-").toRealPath()
            val execution = execution(root, "timeout").copy(command = sleepingCommand(root, timeoutMillis = 10_000L))
            val process = launch(execution)
            try {
                val started = await(execution) {
                    it.operation.output.contains("PROBE_READY") || it.operation.phase in terminal
                }
                assertTrue(started.operation.output.contains("PROBE_READY"), "Foreground child did not reach readiness")
                val child = Files.readString(root.resolve("child.pid")).toLong()
                val result = await(execution) { it.operation.phase in terminal }
                assertEquals(WorktreeBuildPhase.Failed, result.operation.phase)
                assertEquals("BuildTimedOut", result.operation.failure)
                assertTrue(ProcessHandle.of(child).orElse(null)?.isAlive != true)
            } finally {
                stopWorker(process)
                removeTestTree(root)
            }
        }
    }

    @Test
    fun `parent dying during execution terminates child and releases its resource`() = runTest {
        withContext(RealTestDispatchers.io) {
            val root = Files.createTempDirectory("heartbeat-parent-active-test-").toRealPath()
            val parent = ProcessBuilder(
                listOf(javaExecutable(), "-cp", testClasspath(), PROBE_MAIN, root.resolve("parent.pid").toString()),
            ).start()
            val execution = execution(root, "orphan-running").copy(
                command = sleepingCommand(root),
                parentProcess = parent.pid(),
                parentStartedAt = parent.info().startInstant().orElse(null)?.toString(),
            )
            val worker = launch(execution)
            try {
                await(execution) { it.operation.output.contains("PROBE_READY") }
                parent.destroyForcibly()
                parent.waitFor(10, TimeUnit.SECONDS)
                val result = await(execution) { it.operation.phase in terminal }
                assertEquals(WorktreeBuildPhase.Cancelled, result.operation.phase)
                assertEquals("ParentLost", result.operation.failure)
                val child = Files.readString(root.resolve("child.pid")).toLong()
                assertTrue(ProcessHandle.of(child).orElse(null)?.isAlive != true)
                val following = execution(root, "following")
                val next = launch(following)
                try {
                    val completed = await(following) { it.operation.phase in terminal }.operation
                    assertEquals(
                        WorktreeBuildPhase.Completed,
                        completed.phase,
                        "${completed.failure.orEmpty()}: ${completed.output}",
                    )
                } finally {
                    stopWorker(next)
                }
            } finally {
                if (parent.isAlive) parent.destroyForcibly()
                stopWorker(worker)
                removeTestTree(root)
            }
        }
    }

    @Test
    fun `Windows batch wrapper receives foreground quoted arguments`() = runTest {
        if (!isWindows()) return@runTest
        withContext(RealTestDispatchers.io) {
            val root = Files.createTempDirectory("heartbeat-batch-test-").toRealPath()
            Files.writeString(root.resolve("build.cmd"), "@echo off\r\necho ARG=%~1\r\nexit /b 0\r\n")
            val execution = execution(
                root,
                "batch",
            ).copy(command = WorktreeBuildCommand("batch", "build.cmd", listOf("space value")))
            val worker = launch(execution)
            try {
                val result = await(execution) { it.operation.phase in terminal }
                assertEquals(WorktreeBuildPhase.Completed, result.operation.phase)
                assertTrue(result.operation.output.contains("ARG=space value"), result.operation.output)
            } finally {
                stopWorker(worker)
                removeTestTree(root)
            }
        }
    }

    @Test
    fun `worker command cannot inherit secret environment sentinels`() = runTest {
        withContext(RealTestDispatchers.io) {
            val root = Files.createTempDirectory("heartbeat-environment-test-").toRealPath()
            val execution = execution(
                root,
                "environment",
            ).copy(
                command = WorktreeBuildCommand(
                    "environment",
                    javaExecutable(),
                    listOf("-cp", testClasspath(), PROBE_MAIN, "environment"),
                ),
            )
            val worker = launch(execution, mapOf("TEST_ACCESS_TOKEN" to "synthetic-sentinel"))
            try {
                val result = await(execution) { it.operation.phase in terminal }
                assertEquals(WorktreeBuildPhase.Completed, result.operation.phase)
                assertTrue(result.operation.output.contains("SENTINEL_ABSENT"), result.operation.output)
                assertTrue(!result.operation.output.contains("synthetic-sentinel"))
            } finally {
                stopWorker(worker)
                removeTestTree(root)
            }
        }
    }

    private fun stopWorker(process: Process) {
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "TestWorkerDidNotStop" }
        }
        process.inputStream.close()
        process.errorStream.close()
        process.outputStream.close()
    }
    private fun sleepingCommand(root: Path, timeoutMillis: Long = 30_000L): WorktreeBuildCommand = WorktreeBuildCommand(
        "sleep",
        javaExecutable(),
        listOf("-cp", testClasspath(), PROBE_MAIN, root.resolve("child.pid").toString()),
        timeoutMillis = timeoutMillis,
    )

    private fun testClasspath(): String = workerClasspath() + java.io.File.pathSeparator +
        Path.of(WorktreeWorkerTest::class.java.protectionDomain.codeSource.location.toURI())

    private fun javaExecutable(): String = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (isWindows()) "java.exe" else "java",
    ).toString()

    private fun execution(root: Path, id: String, isHold: Boolean = false): BuildExecution = BuildExecution(
        id,
        WorktreeBuildCommand(
            id,
            Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString(),
            listOf("-version"),
        ),
        root.toString(),
        listOf(root.resolve("shared-resource").toString()),
        root.resolve(id).toString(),
        isHold,
        ProcessHandle.current().pid(),
        ProcessHandle.current().info().startInstant().orElse(null)?.toString(),
    )

    private fun launch(execution: BuildExecution, environment: Map<String, String> = emptyMap()): Process {
        val directory = Files.createDirectories(Path.of(execution.jobDirectory))
        Files.writeString(directory.resolve("job.json"), Json.encodeToString(execution))
        val builder = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString(),
            "-cp",
            workerClasspath(),
            "io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorktreeBuildWorkerKt",
            directory.toString(),
        ).redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile())
        builder.environment().putAll(environment)
        return builder.start()
    }

    private suspend fun await(execution: BuildExecution, predicate: (WorkerState) -> Boolean): WorkerState =
        withTimeout(
            20_000L,
        ) {
            val path = Path.of(execution.jobDirectory).resolve("state.json")
            var state: WorkerState? = null
            while (state == null || !predicate(state)) {
                if (Files.exists(path)) state = Json.decodeFromString(Files.readString(path))
                if (state == null || !predicate(state)) delay(25L)
            }
            state
        }

    private companion object {
        const val PROBE_MAIN = "io.aequicor.heartbeat.feature.worktreemode.impl.data.WorktreeWorkerTestKt"
        val terminal = setOf(
            WorktreeBuildPhase.Completed,
            WorktreeBuildPhase.Cancelled,
            WorktreeBuildPhase.Failed,
            WorktreeBuildPhase.Unknown,
        )
        fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}

/** A real child JVM makes worker lifetime checks independent of the test coroutine scheduler. */
fun main(arguments: Array<String>) {
    io.aequicor.heartbeat.core.logging.Log.init(isDebug = true)
    if (arguments.single() == "environment") {
        val message = if (System.getenv("TEST_ACCESS_TOKEN") == null) "SENTINEL_ABSENT" else "SENTINEL_PRESENT"
        io.aequicor.heartbeat.core.logging.Log.tag("WorktreeWorkerProbe").i { message }
        return
    }
    Files.writeString(Path.of(arguments.single()), ProcessHandle.current().pid().toString())
    io.aequicor.heartbeat.core.logging.Log.tag("WorktreeWorkerProbe").i { "PROBE_READY" }
    Thread.sleep(30_000L)
}
