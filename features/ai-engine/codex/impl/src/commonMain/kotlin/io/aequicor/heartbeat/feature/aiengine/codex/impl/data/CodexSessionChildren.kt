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
 * idle process is kept and retirement is rechecked later; an unreadable family does not keep an idle process alive.
 */
internal class CodexSessionChildren(private val session: CodexSession, private val scope: ScopeHandle) {
    private val log = Log.tag("CodexSessionChildren")
    private var isSpawned = false
    private var watch: Job? = null

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
        if (isActive && watch?.isActive != true && !scope.isClosed) {
            log.v { "Codex idle process kept for running subagents" }
            watch = scope.coroutineScope.launch {
                delay(RECHECK_MILLIS)
                session.runtime.release(session)
            }
        }
        return isActive
    }

    private companion object {
        const val RECHECK_MILLIS = 5_000L
    }
}
