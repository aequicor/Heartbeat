package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Captures children before resource disposal can orphan them, then confirms their exit before the JVM exits. */
internal interface DesktopProcessCleanup {
    suspend fun capture()
    suspend fun stop()
}

/**
 * Final process barrier for explicit application quit. Only descendants of this JVM are adopted; retained
 * handles keep their OS identity even when their parent exits during feature cleanup. The application updater
 * deliberately skips this barrier on restart because its installer must outlive the old JVM.
 *
 * Feature owners still stop their own work first. This snapshot is a fallback for live descendants, not OS
 * containment of processes that detached before shutdown began. No process is selected by executable name.
 */
internal class DesktopChildProcesses(
    private val dispatchers: DispatcherProvider,
    private val root: ProcessHandle = ProcessHandle.current(),
    private val timeoutMillis: Long = PROCESS_EXIT_TIMEOUT_MILLIS,
) : DesktopProcessCleanup {
    private val log = Log.tag("DesktopChildProcesses")
    private val children = linkedSetOf<ProcessHandle>()
    private val failures = mutableListOf<Exception>()

    override suspend fun capture() = withContext(dispatchers.io) {
        discover(root)
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        discover(root)
        // A retained child may have been reparented while resources were closing and have children of its own.
        children.toList().forEach(::discover)
        val stopping = children.toList().asReversed()
        stopping.forEach { process ->
            attempt {
                if (process.isAlive) {
                    check(process.info().startInstant().isPresent) { "Child process identity unavailable" }
                    process.destroyForcibly()
                }
            }
        }
        val hasExited = withTimeoutOrNull(timeoutMillis) {
            stopping.forEach { process -> attemptAwait { if (process.isAlive) process.onExit().await() } }
            stopping.none { it.isAlive }
        } ?: false
        check(hasExited) { "Application child processes did not exit within $timeoutMillis ms" }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).filter { it !== failure }.forEach(failure::addSuppressed)
            throw failure
        }
        log.i { "Application child processes stopped count=${stopping.size}" }
    }

    private fun discover(parent: ProcessHandle) {
        attempt {
            if (parent.isAlive) {
                val found = parent.descendants().use { it.toList() }
                // descendants resolves a PID: discard the listing if its original owner exited during inspection.
                if (parent.isAlive) children += found.filter { it != root }
            }
        }
    }

    private fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Could not inspect or stop an application child process" }
            failures += error
        }
    }

    private suspend fun attemptAwait(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Could not await an application child process" }
            failures += error
        }
    }

    private companion object {
        const val PROCESS_EXIT_TIMEOUT_MILLIS = 5_000L
    }
}
