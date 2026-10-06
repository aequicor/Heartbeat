package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

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
internal class PiProcessOwnership(private val root: () -> ProcessHandle, private val dispatchers: DispatcherProvider) {
    private val log = Log.tag("PiProcessOwnership")
    private val launchId = Uuid.random().toString()
    private val mutex = Mutex()
    private var isComplete = true
    private val children = mutableSetOf<PiProcessIdentity>()

    suspend fun capture(): PiExecutionOwner? = withContext(dispatchers.io) {
        mutex.withLock {
            if (!isComplete) return@withLock null
            try {
                snapshot()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(IllegalStateException("Process inspection failed (${error::class.simpleName.orEmpty()})")) {
                    "Pi process ownership unavailable"
                }
                incomplete()
            }
        }
    }

    /** Called only while holding the capture mutex on IO. */
    private fun snapshot(): PiExecutionOwner? {
        val process = root()
        val identity = process.identity() ?: return incomplete()
        val descendants = process.descendants().use { it.toList() }
        val current = descendants.map { it.identity() ?: return incomplete() }
        children += current
        if (!process.isAlive) return incomplete()
        return PiExecutionOwner(launchId, identity, children.toList())
    }

    /** A later empty snapshot cannot repair an earlier gap in the same launch's observed descendants. */
    private fun incomplete(): PiExecutionOwner? {
        isComplete = false
        return null
    }

    private fun ProcessHandle.identity(): PiProcessIdentity? {
        if (!isAlive) return null
        val startedAt = info().startInstant().orElse(null) ?: return null
        return PiProcessIdentity(pid(), startedAt.toString())
    }
}
