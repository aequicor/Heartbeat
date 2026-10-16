package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.ModelProfileStore
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.StoredLibrary
import kotlinx.serialization.Serializable

/** The library in the profile key-value store, one JSON document so descriptions and preferences change together. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueModelProfiles(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : ModelProfileStore {
    private val log = Log.tag("KeyValueModelProfiles")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun load(): StoredLibrary {
        val stored = store.get(KEY)
        log.v { "read model library: ${stored?.profiles?.size ?: 0}" }
        return stored?.let { StoredLibrary(it.profiles, it.preferences) } ?: StoredLibrary()
    }

    override suspend fun save(library: StoredLibrary) {
        log.v { "write model library: ${library.profiles.size}" }
        store.set(KEY, LibraryDocument(library.profiles, library.preferences))
    }

    private companion object {
        val SPEC = KeyValueSpec("adaptive_subagents")
        val KEY = jsonKey("library", LibraryDocument.serializer())
    }
}

@Serializable
private data class LibraryDocument(
    val profiles: List<ModelProfile> = emptyList(),
    val preferences: SubagentPreferences = SubagentPreferences(),
)
