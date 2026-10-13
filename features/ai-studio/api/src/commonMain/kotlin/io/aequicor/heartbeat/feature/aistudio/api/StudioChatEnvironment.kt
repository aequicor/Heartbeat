package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/**
 * Host-specific routing for one outside turn; never decoded from model arguments or prompt text.
 * A native submission with an uncertain outcome retains its binding rather than relaxing tool restrictions.
 */
public interface StudioChatEnvironment {
    /** Declarations for a newly created session, before its native identity exists. */
    public val preselected: Map<String, Set<String>> get() = emptyMap()

    /** Binds the concrete session before its first prompt; closing the lease removes only this binding. */
    public fun bind(session: SessionRef): AutoCloseable
}
