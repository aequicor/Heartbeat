package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeException
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeSession
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.HelloHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Subscriptions extensions hold on a connection. A watch runs in the connection's scope, so it ends with the
 * connection; at most [HelloHandler.MAX_WATCHES] run per connection.
 */
internal class BridgeWatches {
    private val log = Log.tag("BridgeWatches")
    private val lock = Mutex()
    private val bySession = mutableMapOf<Long, MutableMap<String, Job>>()
    private var next = 0L

    /** Starts [body] as watch `w:N`; `null` when the connection holds the maximum already. */
    suspend fun open(session: BridgeSession, body: suspend (watch: String) -> Unit): String? {
        val (watch, job) = lock.withLock {
            val open = bySession.getOrPut(session.id) { mutableMapOf() }
            if (open.size >= HelloHandler.MAX_WATCHES) return null
            val watch = "w:${++next}"
            val job = session.scope.launch(start = CoroutineStart.LAZY) { run(session, watch, body) }
            open[watch] = job
            watch to job
        }
        // Started outside the lock: a watch ending at once forgets itself under the same lock.
        job.start()
        return watch
    }

    private suspend fun run(session: BridgeSession, watch: String, body: suspend (watch: String) -> Unit) {
        try {
            body(watch)
        } catch (e: BridgeException) {
            // The connection went away while an event was being sent; the scope ends right after.
            log.w(e) { "Bridge watch ended id=${session.id} code=${e.code}" }
        } finally {
            withContext(NonCancellable) { forget(session.id, watch) }
        }
    }

    /** Cancels [watch] of [session]; `false` for an unknown watch. */
    suspend fun cancel(session: BridgeSession, watch: String): Boolean {
        val job = lock.withLock { bySession[session.id]?.remove(watch) } ?: return false
        job.cancel()
        return true
    }

    private suspend fun forget(session: Long, watch: String) {
        lock.withLock {
            val open = bySession[session] ?: return@withLock
            open.remove(watch)
            if (open.isEmpty()) bySession.remove(session)
        }
    }
}
