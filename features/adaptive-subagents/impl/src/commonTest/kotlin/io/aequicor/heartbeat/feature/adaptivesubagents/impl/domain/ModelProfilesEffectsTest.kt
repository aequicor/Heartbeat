package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesEffect
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchFailure
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchJob
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchStage
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchSubject
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.ResearchOutcome
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.target
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    @Test
    fun `a research run reports progress then its description`() = runTest {
        val machine = RecordingScope()
        val job = ResearchJob("j1", ResearchSubject(profile.key, "M"), target("pi", "b", "r"))
        val effects = ModelProfilesEffects(MemoryStore(), { _, progress ->
            progress(ResearchStage.Reading, 2)
            ResearchOutcome.Written(profile)
        }) { true }
        effects.handle(ModelProfilesEffect.Research(job), machine)
        assertEquals(
            listOf<ModelProfilesIntent>(
                ModelProfilesIntent.Internal.ResearchProgress("j1", ResearchStage.Reading, 2),
                ModelProfilesIntent.Internal.ResearchCompleted("j1", profile),
            ),
            machine.sent,
        )
    }

    @Test
    fun `a disabled research fails and a stopped run is cancelled`() = runTest {
        val job = ResearchJob("j1", ResearchSubject(profile.key, "M"), target("pi", "b", "r"))
        val off = RecordingScope()
        ModelProfilesEffects(MemoryStore(), { _, _ -> error("unused") }) { false }
            .handle(ModelProfilesEffect.Research(job), off)
        assertEquals(
            listOf<ModelProfilesIntent>(ModelProfilesIntent.Internal.ResearchFailed("j1", ResearchFailure.Failed)),
            off.sent,
        )
        val started = CompletableDeferred<Unit>()
        val effects = ModelProfilesEffects(MemoryStore(), { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }) { true }
        val run = launch { effects.handle(ModelProfilesEffect.Research(job), RecordingScope()) }
        started.await()
        effects.handle(ModelProfilesEffect.StopResearch("j1"), RecordingScope())
        run.join()
        assertTrue(run.isCancelled)
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
