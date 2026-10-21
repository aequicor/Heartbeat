package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * The profile's single permission setting for every agent and tool. It is read at execution, not inherited from
 * a chat, model, helper or restored turn. It does not change argument validity or the capabilities of the host.
 */
@Serializable
public enum class SecurityMode {
    /** Reads run unless [ActionSafetyEnabled] is active; the structured policy asks for every operation. */
    Ask,

    /**
     * Exact host rules approve ordinary operations. With [ActionSafetyEnabled], complete host evidence and
     * original human authority enter two-stage model review; unknown evidence or unavailable review asks the user.
     */
    Automatic,

    /**
     * Correct calls run without model review. With [ActionSafetyEnabled], mandatory analysis denials and hook
     * Deny or Ask remain effective; without it, permission hooks do not run. Host validity checks always apply.
     */
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
