package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.platform.dibundle.createDesktopHostDispatchers
import io.aequicor.heartbeat.platform.dibundle.desktopStorageRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit foreground host, selected before UI or graph initialization in all desktop launchers.
 *
 * Invoke the installed launcher with `--heartbeat-process-worker start [--profile <id>]`; without an id it
 * restores the saved profile. `--heartbeat-process-worker status` and `stop` are independent control clients.
 * First enable `harness.background_worker` in the studio toggle panel. No other toggle or trust level changes.
 * Pi, Codex, Claude and Koog use the same profile runtime as the UI, with their existing credential routes;
 * this is not an interactive login host. Missing authorization or native permission/input pauses the profile.
 * System vault access is noninteractive and never falls back to the development store.
 *
 * The worker owns the complete storage root until confirmed cleanup. UI launches become a monitor without a
 * graph. To transfer back, stop the worker, wait for exit and reopen the UI: it restores the same profile and
 * persisted ownership fences through the normal recovery paths, rather than claiming a new distributed epoch.
 * A retained host-intervention reason prevents unattended restart from retrying a blocked action; explicit UI
 * startup clears the reason but grants no process decision. UI, browser and computer-use surfaces are not
 * provided by this host. The caller keeps the machine and terminal session alive; no service is installed.
 */
internal fun handleProcessWorker(
    arguments: Array<String>,
    isDevelopment: Boolean = false,
    hasLocalSecrets: Boolean = isDevelopment,
): Int? {
    if (arguments.firstOrNull() != PROCESS_WORKER_ARGUMENT) return null
    val command = parseWorkerCommand(arguments.drop(1))
    // Explicit terminal commands must report status even in the packaged production launcher.
    val sink = initializeDesktopLogging(isDebug = true, isTrace = false)
    val isSuccessful = try {
        val root = Path.of(desktopStorageRoot())
        when (command.operation) {
            "start" -> foregroundProcessWorker(root, command.profile, isDevelopment, hasLocalSecrets)

            else -> {
                val status = workerRequest(root, command.operation)
                Log.tag("ProcessWorker").i { status?.encode() ?: "Storage is owned by the desktop UI" }
                status != null
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.tag("ProcessWorker").e(error) { "Worker command failed; application stores were not opened concurrently" }
        false
    } finally {
        sink?.close()
    }
    // AWT's dispatcher must also terminate; no Compose application loop owns this foreground process.
    return if (isSuccessful) 0 else 1
}

internal data class ProcessWorkerCommand(val operation: String, val profile: String? = null)

internal fun parseWorkerCommand(arguments: List<String>): ProcessWorkerCommand {
    require(arguments.firstOrNull() in setOf("start", "status", "stop")) {
        "Use --heartbeat-process-worker start [--profile <id>] | status | stop"
    }
    val operation = arguments.first()
    if (arguments.size == 1) return ProcessWorkerCommand(operation)
    require(operation == "start" && arguments.size == PROFILE_ARGUMENT_COUNT && arguments[1] == "--profile")
    val profile = arguments[2]
    require(profile.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid profile identifier" }
    return ProcessWorkerCommand(operation, profile)
}

private fun foregroundProcessWorker(
    root: Path,
    profile: String?,
    development: Boolean,
    localSecrets: Boolean,
): Boolean {
    val owner = checkNotNull(
        DesktopStorageOwner.claim(root),
    ) { "Storage is already owned; stop the current host first" }
    val lifetime = ForegroundWorkerLifetime(profile, development, localSecrets, createDesktopHostDispatchers())
    val hook = lifetime.shutdownHook()
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        WorkerControl(owner, true, { lifetime.state.value }, { lifetime.stop.complete(Unit) }).use {
            lifetime.start(owner)
            return lifetime.completed.get()
        }
    } finally {
        lifetime.close(owner, hook)
    }
}

private class ForegroundWorkerLifetime(
    private val profile: String?,
    private val isDevelopment: Boolean,
    private val hasLocalSecrets: Boolean,
    private val dispatchers: DispatcherProvider,
) {
    val state = MutableStateFlow(WorkerSnapshot(WorkerPhase.Starting))
    val stop = CompletableDeferred<Unit>()
    val completed = CompletableFuture<Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val mayHoldResources = AtomicBoolean(false)

    fun shutdownHook(): Thread = Thread({
        stop.complete(Unit)
        try {
            completed.get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.tag("ProcessWorker").e(error) { "Worker JVM shutdown did not confirm cleanup" }
        }
    }, "Heartbeat-worker-shutdown")

    fun start(owner: DesktopStorageOwner) {
        scope.launch {
            try {
                val isClean = execute(owner)
                if (isClean) mayHoldResources.set(false)
                completed.complete(isClean)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                completed.completeExceptionally(error)
                Log.tag("ProcessWorker").e(error) { "Worker host failed" }
            }
        }.invokeOnCompletion { error ->
            if (error != null) completed.completeExceptionally(error)
        }
    }

    private suspend fun execute(owner: DesktopStorageOwner): Boolean {
        val waiting = withContext(dispatchers.io) { readWorkerWait(owner.directory) }
        return runStoredProcessWorker(
            waiting,
            stop,
            state,
            createSession = {
                mayHoldResources.set(true)
                DesktopProcessWorkerSession(profile, isDevelopment, hasLocalSecrets)
            },
            persistWait = { reason -> withContext(dispatchers.io) { writeWorkerWait(owner.directory, reason) } },
        )
    }

    fun close(owner: DesktopStorageOwner, hook: Thread) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (error: IllegalStateException) {
            Log.tag("ProcessWorker").w(error) { "JVM shutdown already owns the worker stop barrier" }
        } finally {
            scope.cancel()
            // Failed cleanup retains the lock until JVM exit, never opening a premature handoff window.
            if (mayHoldResources.get()) retainDesktopStorageOwnerUntilExit(owner) else owner.close()
        }
    }
}

/** A tiny host-owned reason, outside script data. Explicit UI startup clears it; no decision is granted by doing so. */
internal fun readWorkerWait(directory: Path): WorkerProblem? {
    val file = directory.resolve(WORKER_WAIT_FILE)
    if (!Files.exists(file)) return null
    require(!Files.isSymbolicLink(file))
    val bytes = Files.newInputStream(file).use { it.readNBytes(WAIT_REASON_MAX_BYTES + 1) }
    require(bytes.size <= WAIT_REASON_MAX_BYTES)
    return WorkerProblem.valueOf(bytes.toString(Charsets.UTF_8)).also { require(it != WorkerProblem.None) }
}

internal fun writeWorkerWait(directory: Path, problem: WorkerProblem) {
    val temporary = Files.createTempFile(directory, "waiting-", ".tmp")
    try {
        privateRuntimePath(temporary)
        Files.writeString(temporary, problem.name)
        Files.move(
            temporary,
            directory.resolve(WORKER_WAIT_FILE),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal const val PROCESS_WORKER_ARGUMENT = "--heartbeat-process-worker"
internal const val WORKER_WAIT_FILE = "waiting"
private const val PROFILE_ARGUMENT_COUNT = 3
private const val WAIT_REASON_MAX_BYTES = 64
private const val SHUTDOWN_WAIT_SECONDS = 180L
