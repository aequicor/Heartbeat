package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Coalesces helper opens and backs off failed recovery for a minute. Polling must not repeatedly launch native
 * processes for an incompatible session. A changed session, target or workspace retries immediately; cancellation
 * never caches failure.
 * This only opens an existing session: unknown evidence never settles a receipt or resubmits its prompt.
 */
@Inject
internal class StudioHelperRecovery(private val clock: Clock) {
    private val lock = Mutex()
    private val attempts = mutableMapOf<String, Attempt>()

    @HighFrequency
    suspend fun open(record: StudioChatRecord, open: suspend () -> ActiveSession): ActiveSession? {
        val attempt = reserve(record) ?: return null
        var retryAt: Instant? = null
        try {
            return open()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            retryAt = clock.now() + 1.minutes
            // The native evidence boundary logs the original failure once per actual open attempt.
            throw error
        } finally {
            withContext(NonCancellable) { release(record.id, attempt, retryAt) }
        }
    }

    @HighFrequency
    private suspend fun reserve(record: StudioChatRecord): Attempt? = lock.withLock {
        val key = Key(record.ref, record.target, record.resolvedExecutionWorkspace())
        val now = clock.now()
        attempts.entries.removeAll { (_, value) -> value.retryAt?.let { now >= it } == true }
        val previous = attempts[record.id]
        if (previous != null) {
            if (previous.retryAt == null) return null
            if (previous.key == key && now < previous.retryAt) return null
        }
        Attempt(key).also { attempts[record.id] = it }
    }

    private suspend fun release(id: String, attempt: Attempt, retryAt: Instant?) = lock.withLock {
        if (attempts[id] === attempt) {
            if (retryAt == null) {
                attempts.remove(id)
            } else {
                attempts[id] = Attempt(attempt.key, retryAt)
            }
        }
    }

    private data class Key(val session: SessionRef?, val target: EngineTarget?, val workspace: WorkspaceRef?)
    private data class Attempt(val key: Key, val retryAt: Instant? = null)
}
