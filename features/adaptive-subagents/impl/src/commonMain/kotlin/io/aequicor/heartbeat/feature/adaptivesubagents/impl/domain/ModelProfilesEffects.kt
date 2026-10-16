package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesEffect
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The stored library: descriptions and preferences, written together. */
internal data class StoredLibrary(
    val profiles: List<ModelProfile> = emptyList(),
    val preferences: SubagentPreferences = SubagentPreferences(),
)

/** Profile storage of the library. */
internal interface ModelProfileStore {
    /** The stored library; empty when nothing was saved. */
    suspend fun load(): StoredLibrary

    /** Replaces the stored library. */
    suspend fun save(library: StoredLibrary)
}

/**
 * Executes library effects; failures are mapped by the spec's `onEffectFailure`. Saves run one at a time and a save
 * older than the last written revision is dropped, so concurrent effects never leave a stale library in the store.
 * Texts of descriptions are never logged.
 */
internal class ModelProfilesEffects(private val store: ModelProfileStore) :
    EffectHandler<ModelProfilesEffect, ModelProfilesIntent> {
    private val log = Log.tag("ModelProfilesEffects")
    private val saving = Mutex()
    private var savedRevision = -1L

    override suspend fun handle(effect: ModelProfilesEffect, machine: EffectScope<ModelProfilesIntent>) {
        when (effect) {
            ModelProfilesEffect.Load -> {
                val library = store.load()
                log.i { "model library loaded: ${library.profiles.size} descriptions" }
                machine.send(ModelProfilesIntent.Internal.Loaded(library.profiles, library.preferences))
            }

            is ModelProfilesEffect.Persist -> saving.withLock {
                if (effect.revision <= savedRevision) {
                    log.d { "stale model library save skipped: ${effect.revision} <= $savedRevision" }
                } else {
                    store.save(StoredLibrary(effect.profiles, effect.preferences))
                    savedRevision = effect.revision
                    log.i { "model library saved: ${effect.profiles.size} descriptions, revision ${effect.revision}" }
                }
            }
        }
    }
}
