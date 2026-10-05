package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventNamespace
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.flow.Flow

/**
 * The prompt that resumes a session. [visible] is what a transcript shows; [directive] is host text for the agent
 * only (sent as a host directive, never shown). [request] is stable per wake, so a host can recognise a repeated
 * delivery.
 */
public data class WakePrompt(val request: RequestId, val visible: String, val directive: String) {
    override fun toString(): String = "WakePrompt(request=$request)"
}

/** A helper session to create next to [parent], in the same [workspace], on [target]. */
public data class SpawnRequest(
    val parent: SessionRef,
    val workspace: WorkspaceRef?,
    val target: EngineTarget,
    val title: String,
    val prompt: WakePrompt,
) {
    override fun toString(): String = "SpawnRequest(target=$target, hasWorkspace=${workspace != null})"
}

/**
 * Delivers scheduler prompts into sessions. Contributed into a profile set: the host with the highest [priority] that
 * [owns] a session wakes it (ai-studio for its chats). The host must show tool calls and handle permissions for the
 * resumed turn. Without an owning host the wake fails as unavailable; there is no unattended engine fallback.
 * Used only by `scheduler:impl`, hosts that own sessions and `platform-main:di-bundle`.
 */
public interface ScheduledSessionHost {
    /** Higher wins among hosts that own a session. */
    public val priority: Int

    /** Whether this host keeps the transcript of [session]. */
    public suspend fun owns(session: SessionRef): Boolean

    /**
     * Submits [prompt] as the next turn of the session of [request], waiting while the session is busy. Returns once
     * the engine accepted the turn; the turn itself belongs to the profile. Throws when the prompt was not accepted.
     */
    public suspend fun wake(request: WakeRequest, prompt: WakePrompt)

    /**
     * Creates a helper session and submits its first prompt; returns the new session once the turn is accepted, or
     * null when this host does not create sessions.
     */
    public suspend fun spawn(request: SpawnRequest): SessionRef? = null
}

/** A platform signal; its key must be in [EventNamespace.System]. */
public data class SourceEvent(val key: EventKey, val payload: String? = null) {
    init {
        require(key.namespace == EventNamespace.System) { "Only system events come from sources" }
        require((payload?.length ?: 0) <= SchedulerLimits.MAX_PAYLOAD) { "Event payload is too long" }
    }
}

/**
 * A source of platform signals, contributed into a profile set. The scheduler collects every source while the
 * profile is open and [SchedulerEnabled][io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled] is on, and
 * publishes its events on the bus. Emit transitions only, never the initial state.
 */
public fun interface SchedulerEventSource {
    /** Signals from now on; a failing flow is logged and restarted. */
    public fun events(): Flow<SourceEvent>
}
