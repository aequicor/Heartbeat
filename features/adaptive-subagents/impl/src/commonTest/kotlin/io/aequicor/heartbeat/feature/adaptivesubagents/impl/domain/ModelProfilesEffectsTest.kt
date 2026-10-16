package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesEffect
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ModelProfilesEffectsTest {
    private val profile = ModelProfile(
        ModelKey(EngineId("pi"), ModelId("m")),
        "M",
        updatedAt = Instant.fromEpochSeconds(1),
    )
    private val preferences = SubagentPreferences(defaultPriority = SubagentPriority.Speed)

    @Test
    fun `a saved library is loaded back with its preferences`() = runTest {
        val store = MemoryStore()
        val machine = RecordingScope()
        val effects = ModelProfilesEffects(store)
        effects.handle(ModelProfilesEffect.Persist(listOf(profile), preferences, revision = 1), machine)
        effects.handle(ModelProfilesEffect.Load, machine)
        assertEquals(
            listOf<ModelProfilesIntent>(ModelProfilesIntent.Internal.Loaded(listOf(profile), preferences)),
            machine.sent,
        )
    }

    @Test
    fun `a save older than the stored revision is dropped`() = runTest {
        val store = MemoryStore()
        val effects = ModelProfilesEffects(store)
        effects.handle(ModelProfilesEffect.Persist(listOf(profile), preferences, revision = 2), RecordingScope())
        effects.handle(ModelProfilesEffect.Persist(emptyList(), SubagentPreferences(), revision = 1), RecordingScope())
        assertEquals(StoredLibrary(listOf(profile), preferences), store.load())
    }

    private class MemoryStore : ModelProfileStore {
        private var stored = StoredLibrary()
        override suspend fun load(): StoredLibrary = stored
        override suspend fun save(library: StoredLibrary) {
            stored = library
        }
    }

    private class RecordingScope : EffectScope<ModelProfilesIntent> {
        val sent = mutableListOf<ModelProfilesIntent>()
        override suspend fun send(intent: ModelProfilesIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
}
