package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityOutput
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityState
import kotlinx.coroutines.flow.Flow

/** The profile machine shared by settings screens and the ordinary studio submission boundary. */
internal typealias StudioSecurityMachine = Machine<StudioSecurityState, StudioSecurityIntent, StudioSecurityOutput>

/** Read-only security settings of this profile, separate from per-chat engine configuration. */
internal interface StudioSecurityPolicy {
    /** Accepted levels, after storage is loaded; errors use Ask and pending writes keep the previous level. */
    val levels: Flow<StudioSecurityLevel>

    /** Waits for the saved choice before a turn starts. The value is a snapshot for that turn. */
    suspend fun level(): StudioSecurityLevel
}

/** Durable setting of the active profile. */
internal interface StudioSecurityStore {
    /** Reads the saved level; absence preserves the initial Automatic policy. */
    suspend fun load(): StudioSecurityLevel?

    /** Atomically replaces the saved level. Failure does not change the machine's effective level. */
    suspend fun save(level: StudioSecurityLevel)
}
