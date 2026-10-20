package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * The profile's single permission setting for every agent and tool. It is read at execution, not inherited from
 * a chat, model, helper or restored turn. It does not change argument validity or the capabilities of the host.
 */
@Serializable
public enum class SecurityMode {
    /** Read operations run; other operations ask the user. */
    Ask,

    /** Exact host rules approve ordinary operations; everything else asks the user. No model decides permission. */
    Automatic,

    /** Correct calls of available tools run without permission hooks, policy vetoes or model reviews. */
    Trust,
}

/** Durable, profile-owned permission setting shared by the settings screen and execution boundary. */
public interface AgentSecuritySettings {
    /** Saved mode and subsequent changes. An unreadable setting uses Ask until storage becomes available again. */
    public val modes: Flow<SecurityMode>

    /** Loads the setting strictly for the settings screen. Absence means Automatic; failures must remain visible. */
    public suspend fun load(): SecurityMode?

    /** Changes the mode for subsequent execution decisions only after the durable write succeeds. */
    public suspend fun save(mode: SecurityMode)
}
