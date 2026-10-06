package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.WakeId

/** A visible session summary; title and routing identities are private and never rendered by toString. */
public data class ScriptSession(
    val session: SessionRef,
    val title: String?,
    val workspace: WorkspaceRef?,
    val target: EngineTarget?,
) {
    override fun toString(): String = "ScriptSession(***)"
}

/** A bounded facade history page read after checking that the harness remains active for [session]. */
public data class ScriptHistory(val session: SessionRef, val page: HistoryPage) {
    override fun toString(): String = "ScriptHistory(***)"
}

/**
 * Identity of a submitted helper attempt. The runtime owns its lease through confirmed native and hosted cleanup;
 * returning or discarding this value does not release quota. A helper may not yet expose a native [session].
 */
public data class ScriptHelper(val helper: HelperId, val request: RequestId, val session: SessionRef? = null) {
    override fun toString(): String = "ScriptHelper(***)"
}

/** Host-mediated session operations; every operation rechecks activation and refuses inaccessible sessions. */
public interface ScriptSessions {
    /** Lists only sessions where this harness is currently active; it never exposes the whole profile index. */
    public suspend fun list(): List<ScriptSession>

    /** Reads a bounded page only while the harness is active for [session]. */
    public suspend fun history(session: SessionRef, page: HistoryPageRequest = HistoryPageRequest()): ScriptHistory

    /**
     * Queues visible, fenced text under harness wake/send quotas and returns the owned wake id, not native
     * acceptance. Forbidden from hooks. The host enforces send-chain depth across callbacks and child jobs.
     */
    public suspend fun send(session: SessionRef, text: String): WakeId

    /**
     * Creates a helper through the common lease. The host journals its identity before prompt, inherits the
     * parent's execution workspace, attaches this harness and caps trust at Ask. A missing parent uses host
     * defaults and never grants wider authority. Forbidden from hooks; approval of this script covers the start.
     */
    public suspend fun spawn(parent: SessionRef? = null, title: String, prompt: String): ScriptHelper
}
