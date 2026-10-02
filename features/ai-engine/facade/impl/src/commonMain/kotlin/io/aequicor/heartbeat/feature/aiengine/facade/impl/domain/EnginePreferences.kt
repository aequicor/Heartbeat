package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import kotlinx.coroutines.flow.Flow

/** What a profile chose in engine management: engines it switched off and launch settings per engine. */
data class ProfileEnginePreferences(
    val disabled: Set<EngineId> = emptySet(),
    val launch: Map<EngineId, LaunchSettings> = emptyMap(),
) {
    /** Launch settings of [engine]; defaults when none were saved. */
    fun launchOf(engine: EngineId): LaunchSettings = launch[engine] ?: LaunchSettings()

    override fun toString(): String = "ProfileEnginePreferences(disabled=${disabled.size}, launch=${launch.size})"
}

/** Persistent engine management choices of one profile. Failures propagate. */
interface EnginePreferences {
    /** Current choices and their changes. */
    fun observe(): Flow<ProfileEnginePreferences>

    /** Current choices. */
    suspend fun load(): ProfileEnginePreferences

    /** Applies [change] atomically and returns the saved choices. */
    suspend fun update(change: (ProfileEnginePreferences) -> ProfileEnginePreferences): ProfileEnginePreferences
}
