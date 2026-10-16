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
 * | Ready | Save | valid, expected revision, within limit | Ready(saved, revision + 1) | Persist |
 * | Ready | Save | otherwise | Ready | Rejected(Invalid / Conflict / Limit) |
 * | Ready | Delete | described | Ready(without it, revision + 1) | Persist |
 * | Ready | SetPreferences | differ | Ready(preferences, revision + 1) | Persist |
 * | Ready | SaveFailed | | Ready | StorageFailed |
 * | Idle / Loading / Failed | Save / Delete / SetPreferences | — | ignored | not known before loading |
 *
 * Every change in Ready stays in the state, so effects running for it continue. Effect failures: Load → LoadFailed,
 * Persist → SaveFailed.
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
                stay { state.saved(intent.profile) }
                effect { state.saved(intent.profile).persist() }
            }
            on<ModelProfilesIntent.Public.Save>(guard = { state.rejectionOf(intent) != null }) {
                output { state.rejectionOf(intent)?.let { ModelProfilesOutput.Rejected(intent.profile.key, it) } }
            }
            on<ModelProfilesIntent.Public.Delete>(guard = { state.profileOf(intent.key) != null }) {
                stay { state.without(intent.key) }
                effect { state.without(intent.key).persist() }
            }
            on<ModelProfilesIntent.Public.SetPreferences>(guard = { intent.preferences != state.preferences }) {
                stay { state.copy(preferences = intent.preferences, revision = state.revision + 1) }
                effect { state.copy(preferences = intent.preferences, revision = state.revision + 1).persist() }
            }
            on<ModelProfilesIntent.Internal.SaveFailed> {
                output { ModelProfilesOutput.StorageFailed }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                ModelProfilesEffect.Load -> ModelProfilesIntent.Internal.LoadFailed
                is ModelProfilesEffect.Persist -> ModelProfilesIntent.Internal.SaveFailed
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

private fun ModelProfilesState.Ready.persist() = ModelProfilesEffect.Persist(profiles, preferences, revision)

/** Stored descriptions that are still valid, one per model, within the library limit. */
private fun List<ModelProfile>.loaded(): List<ModelProfile> =
    asSequence().map { it.normalized() }.filter { it.problem() == null }.distinctBy { it.key }
        .take(ProfileLimits.MAX_PROFILES).toList()
