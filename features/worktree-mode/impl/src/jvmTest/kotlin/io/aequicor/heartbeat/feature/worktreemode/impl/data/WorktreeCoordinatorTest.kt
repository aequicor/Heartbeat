package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorkerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreeCoordinatorTest {
    @Test
    fun `terminal reattachment holds its resource until the recorded worker exits`() = runTest {
        withContext(RealTestDispatchers.io) {
            val fixture = CoordinatorFixture()
            val execution = fixture.probeExecution("terminal-worker")
            val worker = ProcessBuilder(listOf(execution.command.executable) + execution.command.arguments)
                .redirectErrorStream(true).redirectOutput(fixture.root.resolve("probe.log").toFile()).start()
            try {
                val directory = Files.createDirectories(Path.of(execution.jobDirectory))
                val completed = WorktreeBuildOperation(execution.id, execution.command.id, WorktreeBuildPhase.Completed)
                val state = WorkerState(
                    completed,
                    worker.pid(),
                    worker.info().startInstant().orElse(null)?.toString(),
                )
                Files.writeString(directory.resolve("state.json"), Json.encodeToString(state))
                val updates = Channel<WorktreeBuildOperation>(Channel.UNLIMITED)
                val reattached = async { fixture.coordinator.execute(execution, true) { updates.send(it) } }
                awaitOperation(updates, execution.id, WorktreeBuildPhase.Completed)
                val following = fixture.execution("following-terminal")
                val followingResult = async { fixture.coordinator.execute(following, false) { updates.send(it) } }
                awaitOperation(updates, following.id, WorktreeBuildPhase.Queued)
                assertTrue(worker.isAlive)
                assertFalse(reattached.isCompleted)
                assertFalse(Files.exists(Path.of(following.jobDirectory)))
                Files.writeString(fixture.root.resolve("release"), "release")
                assertEquals(WorktreeBuildPhase.Completed, withTimeout(AWAIT_MILLIS) { reattached.await() }.phase)
                assertEquals(WorktreeBuildPhase.Completed, withTimeout(AWAIT_MILLIS) { followingResult.await() }.phase)
                assertFalse(worker.isAlive)
            } finally {
                withContext(NonCancellable) {
                    stopMonitorProbe(worker, fixture.root)
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun `launcher exit keeps the actual worker owned through missing snapshots and its terminal state`() = runTest {
        withContext(RealTestDispatchers.io) {
            val fixture = CoordinatorFixture()
            val execution = fixture.probeExecution("split-launcher")
            val launcher = ProcessBuilder(javaExecutable(), "-version")
                .redirectErrorStream(true).redirectOutput(fixture.root.resolve("launcher.log").toFile()).start()
            val worker = ProcessBuilder(listOf(execution.command.executable) + execution.command.arguments)
                .redirectErrorStream(true).redirectOutput(fixture.root.resolve("probe.log").toFile()).start()
            try {
                assertTrue(launcher.waitFor(10, TimeUnit.SECONDS))
                val running = WorktreeBuildOperation(execution.id, execution.command.id, WorktreeBuildPhase.Running)
                val identity = WorkerState(
                    running,
                    worker.pid(),
                    worker.info().startInstant().orElse(null)?.toString(),
                )
                val state = AtomicReference<WorkerState?>(identity)
                val observations = Channel<Unit>(Channel.UNLIMITED)
                val updates = Channel<WorktreeBuildOperation>(Channel.UNLIMITED)
                val result = async {
                    WorkerProcessMonitor(launcher.toHandle()).await(
                        execution,
                        observe = {
                            observations.send(Unit)
                            state.get()
                        },
                        forward = { updates.send(it) },
                    )
                }
                withTimeout(AWAIT_MILLIS) { repeat(8) { observations.receive() } }
                state.set(null)
                withTimeout(AWAIT_MILLIS) { repeat(8) { observations.receive() } }
                assertTrue(worker.isAlive)
                assertFalse(result.isCompleted)
                state.set(identity.copy(operation = running.copy(phase = WorktreeBuildPhase.Completed)))
                awaitOperation(updates, execution.id, WorktreeBuildPhase.Completed)
                assertFalse(result.isCompleted)
                Files.writeString(fixture.root.resolve("release"), "release")
                assertEquals(WorktreeBuildPhase.Completed, withTimeout(AWAIT_MILLIS) { result.await() }.phase)
                assertFalse(worker.isAlive)
            } finally {
                withContext(NonCancellable) {
                    stopMonitorProbe(worker, fixture.root)
                    stopMonitorProbe(launcher, fixture.root)
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun `overlapping builds remain FIFO after cancelling the first queued build`() = runTest {
        withContext(RealTestDispatchers.io) {
            val fixture = CoordinatorFixture()
            val updates = Channel<WorktreeBuildOperation>(Channel.UNLIMITED)
            val waiter = CoordinatorScope()
            try {
                val holding = fixture.execution("holding", isHold = true)
                fixture.coordinator.acquireLease(holding)
                val cancelled = fixture.execution("cancelled")
                val cancelledResult = waiter.coroutineScope.async {
                    fixture.coordinator.execute(cancelled, false) { updates.send(it) }
                }
                awaitOperation(updates, "cancelled", WorktreeBuildPhase.Queued)
                val next = fixture.execution("next", isHold = true)
                val nextResult = waiter.coroutineScope.async {
                    fixture.coordinator.execute(next, false) { updates.send(it) }
                }
                awaitOperation(updates, "next", WorktreeBuildPhase.Queued)
                val last = fixture.execution("last")
                val lastResult = waiter.coroutineScope.async {
                    fixture.coordinator.execute(last, false) { updates.send(it) }
                }
                awaitOperation(updates, "last", WorktreeBuildPhase.Queued)

                assertTrue(fixture.coordinator.cancel("cancelled"))
                assertEquals(WorktreeBuildPhase.Cancelled, cancelledResult.await().phase)
                assertFalse(Files.exists(Path.of(cancelled.jobDirectory)))
                assertTrue(fixture.coordinator.releaseLease(holding))
                awaitOperation(updates, "next", WorktreeBuildPhase.Running)
                assertFalse(Files.exists(Path.of(last.jobDirectory)))
                assertTrue(fixture.coordinator.cancel("next"))
                assertEquals(WorktreeBuildPhase.Cancelled, nextResult.await().phase)
                assertEquals(WorktreeBuildPhase.Completed, lastResult.await().phase)
            } finally {
                withContext(NonCancellable) {
                    waiter.close()
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun `closing a profile waiter preserves the worker and reattaches its final projection`() = runTest {
        withContext(RealTestDispatchers.io) {
            val fixture = CoordinatorFixture()
            val firstProfile = CoordinatorScope()
            val reopenedProfile = CoordinatorScope()
            val oldUpdates = Channel<WorktreeBuildOperation>(Channel.UNLIMITED)
            val newUpdates = Channel<WorktreeBuildOperation>(Channel.UNLIMITED)
            val oldCalls = AtomicInteger()
            try {
                val execution = fixture.probeExecution("reattach")
                val first = firstProfile.coroutineScope.async {
                    fixture.coordinator.execute(execution, false) {
                        oldCalls.incrementAndGet()
                        oldUpdates.send(it)
                    }
                }
                withTimeout(AWAIT_MILLIS) {
                    oldUpdates.receiveAsFlow().first { it.output.contains(PROBE_READY) }
                }
                val worker = fixture.state(execution).process
                first.cancelAndJoin()
                firstProfile.close()
                oldUpdates.close()
                val previousCalls = oldCalls.get()
                val reattached = reopenedProfile.coroutineScope.async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.coordinator.execute(execution, true) { newUpdates.send(it) }
                }
                // Enqueue replaces the callback before suspending on the still-running worker's result.
                Files.writeString(fixture.root.resolve("release"), "release")
                val result = withTimeout(AWAIT_MILLIS) { reattached.await() }
                assertEquals(WorktreeBuildPhase.Completed, result.phase)
                assertEquals(0, result.exitCode)
                awaitOperation(newUpdates, "reattach", WorktreeBuildPhase.Completed)
                assertEquals(worker, fixture.state(execution).process)
                assertEquals(listOf("launch"), Files.readAllLines(fixture.root.resolve("launches")))
                assertEquals(previousCalls, oldCalls.get())
            } finally {
                withContext(NonCancellable) {
                    firstProfile.close()
                    reopenedProfile.close()
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun `reattachment without a durable worker state stays unknown and never replays the command`() = runTest {
        withContext(RealTestDispatchers.io) {
            val fixture = CoordinatorFixture()
            try {
                val execution = fixture.probeExecution("missing")
                val directory = Files.createDirectories(Path.of(execution.jobDirectory))
                Files.writeString(directory.resolve("job.json"), Json.encodeToString(execution))
                val result = fixture.coordinator.execute(execution, true) {}
                assertEquals(WorktreeBuildPhase.Unknown, result.phase)
                assertEquals("WorkerOutcomeUnknown", result.failure)
                assertFalse(Files.exists(directory.resolve("worker.log")))
                assertFalse(Files.exists(directory.resolve("state.json")))
                assertFalse(Files.exists(fixture.root.resolve("launches")))
            } finally {
                withContext(NonCancellable) { fixture.close() }
            }
        }
    }
}

private const val AWAIT_MILLIS = 20_000L
private const val PROBE_READY = "COORDINATOR_READY"

private fun stopMonitorProbe(process: Process, root: Path) {
    Files.writeString(root.resolve("release"), "release")
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "TestMonitorProbeDidNotStop" }
    }
    process.inputStream.close()
    process.errorStream.close()
    process.outputStream.close()
}

private suspend fun awaitOperation(
    updates: Channel<WorktreeBuildOperation>,
    id: String,
    phase: WorktreeBuildPhase,
): WorktreeBuildOperation = withTimeout(AWAIT_MILLIS) {
    updates.receiveAsFlow().first { it.id == id && it.phase == phase }
}

private class CoordinatorFixture {
    val root: Path = Files.createTempDirectory("heartbeat-coordinator-test-").toRealPath()
    private val app = CoordinatorScope()
    private val executions = mutableListOf<BuildExecution>()
    val coordinator = DesktopWorktreeBuildCoordinator(app, RealTestDispatchers)

    fun execution(id: String, isHold: Boolean = false): BuildExecution = BuildExecution(
        id,
        WorktreeBuildCommand(id, javaExecutable(), listOf("-version"), timeoutMillis = AWAIT_MILLIS),
        root.toString(),
        listOf(root.resolve("shared-resource").toString()),
        root.resolve(id).toString(),
        isHold = isHold,
        parentProcess = ProcessHandle.current().pid(),
        parentStartedAt = ProcessHandle.current().info().startInstant().orElse(null)?.toString(),
    ).also(executions::add)

    fun probeExecution(id: String): BuildExecution {
        val execution = execution(id)
        val classpath = workerClasspath() + java.io.File.pathSeparator +
            Path.of(WorktreeCoordinatorTest::class.java.protectionDomain.codeSource.location.toURI())
        return execution.copy(
            command = WorktreeBuildCommand(
                id,
                javaExecutable(),
                listOf("-cp", classpath, WorktreeCoordinatorProbe::class.java.name, root.toString()),
                timeoutMillis = AWAIT_MILLIS,
            ),
        ).also { executions[executions.lastIndex] = it }
    }

    fun state(execution: BuildExecution): WorkerState = Json.decodeFromString(
        Files.readString(Path.of(execution.jobDirectory).resolve("state.json")),
    )

    suspend fun close() = withContext(NonCancellable) {
        executions.forEach { coordinator.cancel(it.id) }
        executions.forEach { execution ->
            val directory = Path.of(execution.jobDirectory)
            if (Files.exists(directory)) Files.writeString(directory.resolve("cancel.request"), "cancel")
            if (Files.exists(directory.resolve("state.json"))) {
                val state = state(execution)
                check(state.operation.id == execution.id)
                val process = ProcessHandle.of(state.process).orElse(null)
                val isOwned = process?.info()?.startInstant()?.orElse(null)?.toString() == state.startedAt
                if (process?.isAlive == true && isOwned) {
                    process.onExit().get(AWAIT_MILLIS, TimeUnit.MILLISECONDS)
                }
            }
        }
        app.close()
        removeTestTree(root)
    }
}

private class CoordinatorScope : ScopeHandle {
    private val callbacks = mutableListOf<() -> Unit>()
    override val name = "coordinator-test"
    override val coroutineScope = CoroutineScope(SupervisorJob() + RealTestDispatchers.default)
    override val savedState: ScopeSavedState get() = error("Not used")
    override var isClosed = false
        private set
    override fun onClose(action: () -> Unit): DisposableHandle {
        callbacks += action
        return DisposableHandle { callbacks.remove(action) }
    }
    fun close() {
        if (isClosed) return
        isClosed = true
        callbacks.toList().forEach { it() }
        coroutineScope.cancel()
    }
}

private fun javaExecutable(): String = Path.of(
    System.getProperty("java.home"),
    "bin",
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
).toString()

/** A native child records each launch and waits for an explicit release, independent of coroutine virtual time. */
internal object WorktreeCoordinatorProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = Path.of(arguments.single())
        FileSystems.getDefault().newWatchService().use { watcher ->
            root.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
            Files.writeString(
                root.resolve("launches"),
                "launch\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            Log.init(isDebug = true)
            Log.tag("WorktreeCoordinatorProbe").i { PROBE_READY }
            while (!Files.exists(root.resolve("release"))) watcher.take().reset()
        }
    }
}
