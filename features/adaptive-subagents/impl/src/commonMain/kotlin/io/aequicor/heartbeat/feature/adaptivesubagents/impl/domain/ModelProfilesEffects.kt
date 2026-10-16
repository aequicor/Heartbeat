package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesEffect
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchFailure
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchJob
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.ModelResearch
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.ResearchOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * A research run is cancelled by its stop effect; while [isResearchEnabled] is false a run fails at once.
 * Texts of descriptions are never logged.
 */
internal class ModelProfilesEffects(
    private val store: ModelProfileStore,
    private val researcher: ModelResearch = ModelResearch { _, _ -> ResearchOutcome.Failed(ResearchFailure.Failed) },
    private val isResearchEnabled: suspend () -> Boolean = { false },
) : EffectHandler<ModelProfilesEffect, ModelProfilesIntent> {
    private val log = Log.tag("ModelProfilesEffects")
    private val saving = Mutex()
    private var savedRevision = -1L
    private val running = Mutex()
    private val runs = mutableMapOf<String, Job>()

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

            is ModelProfilesEffect.Research -> research(effect.job, machine)

            is ModelProfilesEffect.StopResearch -> running.withLock { runs[effect.jobId] }?.let {
                log.i { "research ${effect.jobId} stopped" }
                it.cancel()
            }
        }
    }

    private suspend fun research(job: ResearchJob, machine: EffectScope<ModelProfilesIntent>) {
        val id = job.id
        running.withLock { runs[id] = checkNotNull(currentCoroutineContext()[Job]) }
        try {
            val outcome = if (isResearchEnabled()) {
                researcher.research(job) { stage, sources ->
                    machine.send(ModelProfilesIntent.Internal.ResearchProgress(id, stage, sources))
                }
            } else {
                ResearchOutcome.Failed(ResearchFailure.Failed)
            }
            log.i { "research $id finished: ${if (outcome is ResearchOutcome.Written) "written" else "failed"}" }
            machine.send(
                when (outcome) {
                    is ResearchOutcome.Written -> ModelProfilesIntent.Internal.ResearchCompleted(id, outcome.profile)
                    is ResearchOutcome.Failed -> ModelProfilesIntent.Internal.ResearchFailed(id, outcome.failure)
                },
            )
        } finally {
            withContext(NonCancellable) { running.withLock { runs.remove(id) } }
        }
    }
}
