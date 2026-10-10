package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Captures the live root and all descendants visible before each prompt, retaining earlier observations if a
 * wrapper child is later reparented. Inspection is read-only and never resolves a process by a stored PID.
 * Any incomplete inspection returns null; callers may still execute but cannot promise a recoverable stop.
 */
internal class CodexProcessOwnership(
    private val root: () -> ProcessHandle,
    private val dispatchers: DispatcherProvider,
) {
    private val log = Log.tag("CodexProcessOwnership")
    private val launchId = Uuid.random().toString()
    private val mutex = Mutex()
    private var isComplete = true
    private val children = mutableSetOf<CodexProcessIdentity>()

    suspend fun capture(): CodexExecutionOwner? = withContext(dispatchers.io) {
        mutex.withLock {
            if (!isComplete) return@withLock null
            try {
                snapshot()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(IllegalStateException("Process inspection failed (${error::class.simpleName.orEmpty()})")) {
                    "Codex process ownership unavailable"
                }
                incomplete()
            }
        }
    }

    /** Called only while holding the capture mutex on IO. */
    private fun snapshot(): CodexExecutionOwner? {
        val process = root()
        val identity = process.identity() ?: return incomplete()
        val descendants = process.descendants().use { it.toList() }
        val current = descendants.map { it.identity() ?: return incomplete() }
        children += current
        if (!process.isAlive) return incomplete()
        return CodexExecutionOwner(launchId, identity, children.toList())
    }

    /** A later empty snapshot cannot repair an earlier gap in the same launch's observed descendants. */
    private fun incomplete(): CodexExecutionOwner? {
        isComplete = false
        return null
    }

    private fun ProcessHandle.identity(): CodexProcessIdentity? {
        if (!isAlive) return null
        val startedAt = info().startInstant().orElse(null) ?: return null
        return CodexProcessIdentity(pid(), startedAt.toString())
    }
}
