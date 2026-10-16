package io.aequicor.heartbeat.feature.adaptivesubagents.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Holds the profile's model descriptions and subagent preferences and persists every change.
 *
 * | From | Intent | Guard | To | Effect / output |
 * |---|---|---|---|---|
 * | Idle | Start | | Loading | Load |
 * | Loading | Loaded | | Ready(valid descriptions) | |
 * | Loading | LoadFailed | | Failed | StorageFailed |
 * | Failed | Reload | | Loading | Load |
 * | Ready | Save | valid, matching revision, fits | Ready(saved, revision + 1, −job) | Persist, StopResearch? |
 * | Ready | Save | otherwise | Ready | Rejected(Invalid / Conflict / Limit) |
 * | Ready | Delete | described | Ready(without it, revision + 1, −job) | Persist, StopResearch if running |
 * | Ready | SetPreferences | differ | Ready(preferences, revision + 1) | Persist |
 * | Ready | SaveFailed | | Ready | StorageFailed |
 * | Ready | Research | model not researched, fewer than MAX_JOBS runs | Ready(+job) | Research(job) |
 * | Ready | Research | otherwise | Ready | ResearchFinished(Busy) |
 * | Ready | ResearchProgress | its job runs | Ready(job stage) | |
 * | Ready | ResearchCompleted | its job runs, valid, fits | Ready(−job, +description, rev + 1) | Persist, Finished |
 * | Ready | ResearchCompleted | its job runs, otherwise | Ready(−job) | ResearchFinished(InvalidAnswer) |
 * | Ready | ResearchFailed | its job runs | Ready(−job) | ResearchFinished(failure) |
 * | Ready | CancelResearch | its job runs | Ready(−job) | StopResearch, ResearchFinished(Cancelled) |
 * | Ready | ResearchProgress / Completed / Failed | job gone (cancelled) | ignored | |
 * | Idle / Loading / Failed | Save / Delete / SetPreferences | — | ignored | not known before loading |
 *
 * Changes stay in Ready. Successful manual saves and deletions remove that model's research job before stopping
 * its effect, so a late research result cannot overwrite the edit or recreate the deleted description. Other
 * research jobs continue. Effect failures:
 * Load → LoadFailed, Persist → SaveFailed, Research → ResearchFailed(Failed).
 */
public val ModelProfilesMachineSpec: ModelProfilesSpec =
    machineSpec(ModelProfilesMachineKey, ModelProfilesState.Idle) {
        state<ModelProfilesState.Idle> {
            on<ModelProfilesIntent.Public.Start> {
                goto<ModelProfilesState.Loading> { ModelProfilesState.Loading }
                effect { ModelProfilesEffect.Load }
            }
        }
        state<ModelProfilesState.Loading> {
            on<ModelProfilesIntent.Internal.Loaded> {
                goto<ModelProfilesState.Ready> {
                    ModelProfilesState.Ready(intent.profiles.loaded(), intent.preferences)
                }
            }
            on<ModelProfilesIntent.Internal.LoadFailed> {
                goto<ModelProfilesState.Failed> { ModelProfilesState.Failed }
                output { ModelProfilesOutput.StorageFailed }
            }
        }
        state<ModelProfilesState.Failed> {
            on<ModelProfilesIntent.Public.Reload> {
                goto<ModelProfilesState.Loading> { ModelProfilesState.Loading }
                effect { ModelProfilesEffect.Load }
            }
        }
        state<ModelProfilesState.Ready> {
            on<ModelProfilesIntent.Public.Save>(guard = { state.rejectionOf(intent) == null }) {
                stay { state.saved(intent.profile).withoutResearch(intent.profile.key) }
                effect { state.saved(intent.profile).persist() }
                effect { state.researchOf(intent.profile.key)?.let { ModelProfilesEffect.StopResearch(it.id) } }
            }
            on<ModelProfilesIntent.Public.Save>(guard = { state.rejectionOf(intent) != null }) {
                output { state.rejectionOf(intent)?.let { ModelProfilesOutput.Rejected(intent.profile.key, it) } }
            }
            on<ModelProfilesIntent.Public.Delete>(guard = { state.profileOf(intent.key) != null }) {
                stay { state.without(intent.key).withoutResearch(intent.key) }
                effect { state.without(intent.key).persist() }
                effect { state.researchOf(intent.key)?.let { ModelProfilesEffect.StopResearch(it.id) } }
            }
            on<ModelProfilesIntent.Public.SetPreferences>(guard = { intent.preferences != state.preferences }) {
                stay { state.copy(preferences = intent.preferences, revision = state.revision + 1) }
                effect { state.copy(preferences = intent.preferences, revision = state.revision + 1).persist() }
            }
            on<ModelProfilesIntent.Internal.SaveFailed> {
                output { ModelProfilesOutput.StorageFailed }
            }
            on<ModelProfilesIntent.Public.Research>(guard = { state.canResearch(intent.subject.key) }) {
                stay { state.copy(research = state.research + intent.job()) }
                effect { ModelProfilesEffect.Research(intent.job()) }
            }
            on<ModelProfilesIntent.Public.Research>(guard = { !state.canResearch(intent.subject.key) }) {
                output { ModelProfilesOutput.ResearchFinished(intent.subject.key, ResearchFailure.Busy) }
            }
            on<ModelProfilesIntent.Internal.ResearchProgress>(guard = { state.jobOf(intent.jobId) != null }) {
                stay {
                    state.copy(
                        research = state.research.map {
                            if (it.id == intent.jobId) it.copy(stage = intent.stage, sources = intent.sources) else it
                        },
                    )
                }
            }
            on<ModelProfilesIntent.Internal.ResearchCompleted>(guard = { state.acceptsResearch(intent) }) {
                stay { state.researchedAndSaved(intent) }
                effect { state.researchedAndSaved(intent).persist() }
                output { ModelProfilesOutput.ResearchFinished(intent.profile.key, null) }
            }
            on<ModelProfilesIntent.Internal.ResearchCompleted>(
                guard = { state.jobOf(intent.jobId) != null && !state.acceptsResearch(intent) },
            ) {
                stay { state.researched(intent) }
                output { ModelProfilesOutput.ResearchFinished(intent.profile.key, ResearchFailure.InvalidAnswer) }
            }
            on<ModelProfilesIntent.Internal.ResearchFailed>(guard = { state.jobOf(intent.jobId) != null }) {
                stay { state.copy(research = state.research.filterNot { it.id == intent.jobId }) }
                output {
                    state.jobOf(intent.jobId)?.let {
                        ModelProfilesOutput.ResearchFinished(it.subject.key, intent.failure)
                    }
                }
            }
            on<ModelProfilesIntent.Public.CancelResearch>(guard = { state.researchOf(intent.key) != null }) {
                stay { state.copy(research = state.research.filterNot { it.subject.key == intent.key }) }
                effect { state.researchOf(intent.key)?.let { ModelProfilesEffect.StopResearch(it.id) } }
                output { ModelProfilesOutput.ResearchFinished(intent.key, ResearchFailure.Cancelled) }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                ModelProfilesEffect.Load -> ModelProfilesIntent.Internal.LoadFailed

                is ModelProfilesEffect.Persist -> ModelProfilesIntent.Internal.SaveFailed

                is ModelProfilesEffect.Research ->
                    ModelProfilesIntent.Internal.ResearchFailed(effect.job.id, ResearchFailure.Failed)

                is ModelProfilesEffect.StopResearch -> null
            }
        }
    }

/** Spec type of the library machine. */
public typealias ModelProfilesSpec = MachineSpec<
    ModelProfilesState,
    ModelProfilesIntent,
    ModelProfilesEffect,
    ModelProfilesOutput,
>

/** Why [save] would be rejected, or null when it applies. */
internal fun ModelProfilesState.Ready.rejectionOf(save: ModelProfilesIntent.Public.Save): SaveRejection? {
    val existing = profileOf(save.profile.key)
    return when {
        save.profile.normalized().problem() != null -> SaveRejection.Invalid
        existing?.revision != save.expectedRevision -> SaveRejection.Conflict
        existing == null && profiles.size >= ProfileLimits.MAX_PROFILES -> SaveRejection.Limit
        else -> null
    }
}

private fun ModelProfilesState.Ready.saved(profile: ModelProfile): ModelProfilesState.Ready {
    val stored = profile.normalized().copy(revision = (profileOf(profile.key)?.revision ?: 0) + 1)
    val replaced = profiles.map { if (it.key == stored.key) stored else it }
    return copy(
        profiles = if (replaced.any { it.key == stored.key }) replaced else profiles + stored,
        revision = revision + 1,
    )
}

private fun ModelProfilesState.Ready.without(key: ModelKey): ModelProfilesState.Ready =
    copy(profiles = profiles.filterNot { it.key == key }, revision = revision + 1)

private fun ModelProfilesState.Ready.withoutResearch(key: ModelKey): ModelProfilesState.Ready =
    copy(research = research.filterNot { it.subject.key == key })

private fun ModelProfilesState.Ready.persist() = ModelProfilesEffect.Persist(profiles, preferences, revision)

private fun ModelProfilesState.Ready.canResearch(key: ModelKey): Boolean =
    researchOf(key) == null && research.size < ResearchLimits.MAX_JOBS

private fun ModelProfilesState.Ready.jobOf(id: String): ResearchJob? = research.firstOrNull { it.id == id }

/** A finished research is saved when its job runs, the description is of its model, valid and fits the library. */
private fun ModelProfilesState.Ready.acceptsResearch(done: ModelProfilesIntent.Internal.ResearchCompleted): Boolean {
    val job = jobOf(done.jobId) ?: return false
    val key = done.profile.key
    return key == job.subject.key && done.profile.normalized().problem() == null &&
        (profileOf(key) != null || profiles.size < ProfileLimits.MAX_PROFILES)
}

private fun ModelProfilesState.Ready.researched(done: ModelProfilesIntent.Internal.ResearchCompleted) =
    copy(research = research.filterNot { it.id == done.jobId })

private fun ModelProfilesState.Ready.researchedAndSaved(done: ModelProfilesIntent.Internal.ResearchCompleted) =
    researched(done).saved(done.profile.copy(source = ProfileSource.Research))

private fun ModelProfilesIntent.Public.Research.job() = ResearchJob(jobId, subject, researcher)

/** Stored descriptions that are still valid, one per model, within the library limit. */
private fun List<ModelProfile>.loaded(): List<ModelProfile> =
    asSequence().map { it.normalized() }.filter { it.problem() == null }.distinctBy { it.key }
        .take(ProfileLimits.MAX_PROFILES).toList()
