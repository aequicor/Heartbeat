package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineMcpEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.DeveloperFlags
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineFlags
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EnginePreferences
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineToggles
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ProfileEngineConfig
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ProfileEnginePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
        log.i {
            "save engine preferences disabled=${updated.disabled.size} launch=${updated.launch.size} " +
                "config=${updated.config.size}"
        }
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

    override fun configuration(): Flow<Boolean> = toggles.observe(EngineConfigurationEnabled).distinctUntilChanged()

    override fun developer(descriptor: EngineDescriptor): Flow<DeveloperFlags> =
        combine(toggles.observe(AiEngines), toggles.observe(descriptor.toggle), toggles.observe(EngineMcpEnabled)) {
            catalog,
            own,
            mcp,
            ->
            DeveloperFlags(catalog, own, mcp)
        }.distinctUntilChanged()
}

/**
 * [EngineLaunchConfig] of the profile: saved launch settings, Heartbeat's managed copy and, while
 * [EngineConfigurationEnabled] is on too, the engine's configuration; the adapter defaults while engine management is
 * off. Managed copies are read from disk on the first request.
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
                load()
                isLoaded = true
            }
        }
        val isConfigured = toggles.get(EngineConfigurationEnabled)
        val saved = preferences.load().contextOf(engine, installs.state.value[engine], isConfigured)
        val context = if (toggles.get(EngineMcpEnabled)) {
            saved
        } else {
            saved.copy(settings = saved.settings.copy(isMcpEnabled = false))
        }
        log.d { "launch context engine=${engine.value} $context" }
        return context
    }

    /** Unreadable copies leave the engines on their other installations instead of failing every start. */
    private suspend fun load() {
        try {
            installs.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ManagementException) {
            log.w(e) { "managed copies could not be read; engines start without them" }
        }
    }
}

@Serializable
private data class StoredEnginePreferences(
    val disabled: Set<String> = emptySet(),
    val launch: Map<String, LaunchSettings> = emptyMap(),
    val config: Map<String, StoredEngineConfig> = emptyMap(),
) {
    companion object {
        fun of(preferences: ProfileEnginePreferences) = StoredEnginePreferences(
            preferences.disabled.mapTo(mutableSetOf()) { it.value },
            preferences.launch.filterValues { !it.isDefault }.mapKeys { (engine, _) -> engine.value },
            preferences.config.filterValues { it != ProfileEngineConfig() }
                .map { (engine, config) -> engine.value to StoredEngineConfig.of(config) }
                .toMap(),
        )
    }
}

/** Stored configuration of one engine; documents keep the user's JSON as it was saved. */
@Serializable
private data class StoredEngineConfig(
    val documents: Map<String, StoredDocument> = emptyMap(),
    val secrets: Map<String, Long> = emptyMap(),
    val revision: Long = 0,
    val trust: Map<String, ProjectTrust> = emptyMap(),
) {
    fun toDomain() = ProfileEngineConfig(
        EngineConfigValues(
            documents.map { (id, document) -> ConfigDocumentId(id) to document.toDomain() }.toMap(),
            secrets,
        ),
        revision,
        trust.filterValues { it != ProjectTrust.Undecided }.mapKeys { (workspace, _) -> WorkspaceRef(workspace) },
    )

    companion object {
        fun of(config: ProfileEngineConfig) = StoredEngineConfig(
            config.values.documents.map { (id, document) -> id.value to StoredDocument.of(document) }.toMap(),
            config.values.secrets,
            config.revision,
            config.trust.filterValues { it != ProjectTrust.Undecided }.mapKeys { (workspace, _) -> workspace.value },
        )
    }
}

@Serializable
private sealed interface StoredDocument {
    fun toDomain(): ConfigDocument

    @Serializable
    @SerialName("json")
    data class Json(val value: JsonObject) : StoredDocument {
        override fun toDomain() = ConfigDocument.Json(value)
    }

    @Serializable
    @SerialName("text")
    data class Text(val value: String) : StoredDocument {
        override fun toDomain() = ConfigDocument.Text(value)
    }

    companion object {
        fun of(document: ConfigDocument): StoredDocument = when (document) {
            is ConfigDocument.Json -> Json(document.value)
            is ConfigDocument.Text -> Text(document.value)
        }
    }
}

private fun StoredEnginePreferences?.toDomain(): ProfileEnginePreferences = if (this == null) {
    ProfileEnginePreferences()
} else {
    ProfileEnginePreferences(
        disabled.mapTo(mutableSetOf(), ::EngineId),
        launch.mapKeys { (id, _) -> EngineId(id) },
        config.map { (id, stored) -> EngineId(id) to stored.toDomain() }.toMap(),
    )
}
