package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.DeveloperFlags
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineFlags
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EnginePreferences
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineToggles
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ProfileEnginePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Profile key-value store of engine management; launch settings may name user folders, so values are not logged. */
internal val EngineManagementSpec = KeyValueSpec("aiengine_management")
private val PreferencesKey = jsonKey("preferences", StoredEnginePreferences.serializer())

/** [EnginePreferences] in the profile key-value store; logs only counts. */
class EngineManagementStorage(private val store: KeyValueStore) : EnginePreferences {
    private val log = Log.tag("EngineManagementStorage")
    private val mutex = Mutex()

    override fun observe(): Flow<ProfileEnginePreferences> {
        log.d { "observe engine preferences" }
        return store.observe(PreferencesKey).map { it.toDomain() }.distinctUntilChanged()
    }

    override suspend fun load(): ProfileEnginePreferences {
        log.d { "load engine preferences" }
        return store.get(PreferencesKey).toDomain()
    }

    override suspend fun update(
        change: (ProfileEnginePreferences) -> ProfileEnginePreferences,
    ): ProfileEnginePreferences = mutex.withLock {
        val updated = change(store.get(PreferencesKey).toDomain())
        log.i { "save engine preferences disabled=${updated.disabled.size} launch=${updated.launch.size}" }
        store.set(PreferencesKey, StoredEnginePreferences.of(updated))
        updated
    }
}

/**
 * [EngineToggles] combining the developer flags with the profile switch: an engine runs only while [AiEngines] and
 * its own flag are on and, while [EngineManagementEnabled] is on, the profile has not switched it off.
 */
class ProfileEngineGate(private val toggles: FeatureToggles, private val preferences: EnginePreferences) :
    EngineToggles {
    private val log = Log.tag("EngineToggles")

    override fun observe(descriptor: EngineDescriptor): Flow<Boolean> = combine(
        toggles.observe(AiEngines),
        toggles.observe(descriptor.toggle),
        toggles.observe(EngineManagementEnabled),
        preferences.observe(),
    ) { catalog, own, management, chosen -> catalog && own && !(management && descriptor.id in chosen.disabled) }
        .distinctUntilChanged()

    override suspend fun isEnabled(descriptor: EngineDescriptor): Boolean {
        val isFlagged = toggles.get(AiEngines) && toggles.get(descriptor.toggle)
        val isUserDisabled = isFlagged && toggles.get(EngineManagementEnabled) &&
            descriptor.id in preferences.load().disabled
        val isEnabled = isFlagged && !isUserDisabled
        log.d { "engine gate engine=${descriptor.id.value} enabled=$isEnabled userDisabled=$isUserDisabled" }
        return isEnabled
    }
}

/** [EngineFlags] over the app's feature toggles. */
class ToggleEngineFlags(private val toggles: FeatureToggles) : EngineFlags {
    override fun management(): Flow<Boolean> = toggles.observe(EngineManagementEnabled).distinctUntilChanged()

    override fun developer(descriptor: EngineDescriptor): Flow<DeveloperFlags> =
        combine(toggles.observe(AiEngines), toggles.observe(descriptor.toggle), ::DeveloperFlags).distinctUntilChanged()
}

/**
 * [EngineLaunchConfig] of the profile: saved launch settings and Heartbeat's managed copy, or the adapter defaults
 * while engine management is off. Managed copies are read from disk on the first request.
 */
class ProfileLaunchConfig(
    private val toggles: FeatureToggles,
    private val preferences: EnginePreferences,
    private val installs: ManagedInstallStore,
) : EngineLaunchConfig {
    private val log = Log.tag("EngineLaunchConfig")
    private val mutex = Mutex()
    private var isLoaded = false

    override suspend fun context(engine: EngineId): LaunchContext {
        if (!toggles.get(EngineManagementEnabled)) return LaunchContext()
        mutex.withLock {
            if (!isLoaded) {
                installs.refresh()
                isLoaded = true
            }
        }
        val context = LaunchContext(preferences.load().launchOf(engine), installs.state.value[engine])
        log.d { "launch context engine=${engine.value} $context" }
        return context
    }
}

@Serializable
private data class StoredEnginePreferences(
    val disabled: Set<String> = emptySet(),
    val launch: Map<String, LaunchSettings> = emptyMap(),
) {
    companion object {
        fun of(preferences: ProfileEnginePreferences) = StoredEnginePreferences(
            preferences.disabled.mapTo(mutableSetOf()) { it.value },
            preferences.launch.filterValues { !it.isDefault }.mapKeys { (engine, _) -> engine.value },
        )
    }
}

private fun StoredEnginePreferences?.toDomain(): ProfileEnginePreferences = if (this == null) {
    ProfileEnginePreferences()
} else {
    ProfileEnginePreferences(disabled.mapTo(mutableSetOf(), ::EngineId), launch.mapKeys { (id, _) -> EngineId(id) })
}
