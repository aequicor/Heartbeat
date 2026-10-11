package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Native subagents of a session run in its execution process and outlive their root turn. While one is active the
 * idle process is kept and retirement is rechecked with a growing interval; an unreadable family does not keep an
 * idle process alive. Retention is bounded: after [MAX_RETENTION_MILLIS] of continuous activity the idle process
 * retires, ending the subagents still running in it.
 */
internal class CodexSessionChildren(private val session: CodexSession, private val scope: ScopeHandle) {
    private val log = Log.tag("CodexSessionChildren")
    private var isSpawned = false
    private var watch: Job? = null
    private var interval = RECHECK_MILLIS
    private var keptMillis = 0L
    private var isContinued = false

    /** A subagent item of this thread, or a frame for another thread loaded by this execution process. */
    @HighFrequency
    fun observe(message: JsonObject) {
        if (isSpawned) return
        val params = message["params"] as? JsonObject ?: return
        val thread = params.text("threadId")
        val item = params["item"] as? JsonObject
        isSpawned = (thread != null && thread != session.ref.nativeId) || item?.agentIds()?.isNotEmpty() == true
    }

    suspend fun isActive(): Boolean {
        if (!isSpawned) return false
        val origin = session.connection
        val isActive = try {
            !origin.isClosed && session.runtime.trees.hasActiveDescendants(session.ref, origin.rpc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex subagent activity unavailable; the idle process may retire" }
            false
        }
        // Only the recheck scheduled here continues a retention period; any other release starts a new one.
        if (!isActive || !isContinued) {
            interval = RECHECK_MILLIS
            keptMillis = 0L
        }
        isContinued = false
        val isKept = isActive && keptMillis < MAX_RETENTION_MILLIS
        if (isActive && !isKept) log.i { "Codex idle process retires with running subagents: retention limit reached" }
        if (isKept && watch?.isActive != true && !scope.isClosed) {
            log.v { "Codex idle process kept for running subagents" }
            val wait = interval
            interval = (interval * 2).coerceAtMost(MAX_RECHECK_MILLIS)
            watch = scope.coroutineScope.launch {
                delay(wait)
                keptMillis += wait
                isContinued = true
                session.runtime.release(session)
            }
        }
        return isKept
    }

    internal companion object {
        const val RECHECK_MILLIS = 5_000L
        const val MAX_RECHECK_MILLIS = 60_000L
        const val MAX_RETENTION_MILLIS = 2 * 60 * 60 * 1_000L
    }
}
