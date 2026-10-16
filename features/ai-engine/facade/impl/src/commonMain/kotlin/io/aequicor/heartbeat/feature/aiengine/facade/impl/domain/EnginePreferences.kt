package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import kotlinx.coroutines.flow.Flow

/**
 * What a profile chose in engine management: engines it switched off, launch settings and the configuration of
 * each engine.
 */
data class ProfileEnginePreferences(
    val disabled: Set<EngineId> = emptySet(),
    val launch: Map<EngineId, LaunchSettings> = emptyMap(),
    val config: Map<EngineId, ProfileEngineConfig> = emptyMap(),
) {
    /** Launch settings of [engine]; defaults when none were saved. */
    fun launchOf(engine: EngineId): LaunchSettings = launch[engine] ?: LaunchSettings()

    /** Configuration of [engine]; empty when none was saved. */
    fun configOf(engine: EngineId): ProfileEngineConfig = config[engine] ?: ProfileEngineConfig()

    /**
     * What the adapter of [engine] starts with: launch settings, the [managed] copy and, while [isConfigured], the
     * engine's configuration and project trust decisions.
     */
    fun contextOf(engine: EngineId, managed: ManagedInstall?, isConfigured: Boolean): LaunchContext {
        val chosen = if (isConfigured) configOf(engine) else ProfileEngineConfig()
        return LaunchContext(launchOf(engine), managed, chosen.values, chosen.trust)
    }

    override fun toString(): String =
        "ProfileEnginePreferences(disabled=${disabled.size}, launch=${launch.size}, config=${config.size})"
}

/**
 * Profile configuration of one engine: values of its schema, their [revision] (incremented by every save) and the
 * trust decisions per project. Undecided projects are absent.
 */
data class ProfileEngineConfig(
    val values: EngineConfigValues = EngineConfigValues(),
    val revision: Long = 0,
    val trust: Map<WorkspaceRef, ProjectTrust> = emptyMap(),
) {
    override fun toString(): String = "ProfileEngineConfig(values=$values, revision=$revision, trust=${trust.size})"
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
