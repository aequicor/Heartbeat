package io.aequicor.heartbeat.feature.adaptivesubagents.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.test.Test
import kotlin.time.Instant

class ModelProfilesMachineTest {
    private val spec = ModelProfilesMachineSpec
    private val at = Instant.fromEpochSeconds(100)
    private val key = ModelKey(EngineId("codex"), ModelId("gpt-5"))
    private val other = ModelKey(EngineId("claude"), ModelId("sonnet"))
    private val profile = ModelProfile(key, "GPT-5", "Strong coder", updatedAt = at)
    private val stored = profile.copy(revision = 1)
    private val preferences = SubagentPreferences(defaultPriority = SubagentPriority.Cost)

    @Test
    fun `start loads the stored library and keeps only valid descriptions`() {
        spec.assertTransition(
            ModelProfilesState.Idle,
            ModelProfilesIntent.Public.Start,
            ModelProfilesState.Loading,
            effects = listOf(ModelProfilesEffect.Load),
        )
        val invalid = profile.copy(key = other, tags = setOf("Not A Tag"))
        spec.assertTransition(
            ModelProfilesState.Loading,
            ModelProfilesIntent.Internal.Loaded(listOf(stored, stored.copy(title = "Duplicate"), invalid), preferences),
            ModelProfilesState.Ready(listOf(stored), preferences),
        )
    }

    @Test
    fun `a failed load keeps the library untouched until a reload`() {
        spec.assertTransition(
            ModelProfilesState.Loading,
            ModelProfilesIntent.Internal.LoadFailed,
            ModelProfilesState.Failed,
            outputs = listOf(ModelProfilesOutput.StorageFailed),
        )
        spec.assertTransition(
            ModelProfilesState.Failed,
            ModelProfilesIntent.Public.Reload,
            ModelProfilesState.Loading,
            effects = listOf(ModelProfilesEffect.Load),
        )
        spec.assertIgnored(ModelProfilesState.Failed, ModelProfilesIntent.Public.Save(profile, null))
    }

    @Test
    fun `a new description is normalized stored and persisted`() {
        val edited = profile.copy(title = "  GPT-5  ", tags = setOf("Coding", " "))
        val saved = profile.copy(tags = setOf("coding"), revision = 1)
        spec.assertTransition(
            ModelProfilesState.Ready(),
            ModelProfilesIntent.Public.Save(edited, expectedRevision = null),
            ModelProfilesState.Ready(listOf(saved), revision = 1),
            effects = listOf(ModelProfilesEffect.Persist(listOf(saved), SubagentPreferences(), 1)),
        )
    }

    @Test
    fun `an edit of the stored revision replaces the description in place`() {
        val ready = ModelProfilesState.Ready(listOf(stored, profile.copy(key = other, revision = 4)), revision = 7)
        val updated = stored.copy(summary = "Faster now", revision = 2)
        val after = listOf(updated, profile.copy(key = other, revision = 4))
        spec.assertTransition(
            ready,
            ModelProfilesIntent.Public.Save(stored.copy(summary = "Faster now"), expectedRevision = 1),
            ModelProfilesState.Ready(after, revision = 8),
            effects = listOf(ModelProfilesEffect.Persist(after, SubagentPreferences(), 8)),
        )
    }

    @Test
    fun `stale invalid and over the limit saves are rejected without changes`() {
        val ready = ModelProfilesState.Ready(listOf(stored))
        spec.assertTransition(
            ready,
            ModelProfilesIntent.Public.Save(stored.copy(summary = "Late"), expectedRevision = null),
            ready,
            outputs = listOf(ModelProfilesOutput.Rejected(key, SaveRejection.Conflict)),
        )
        spec.assertTransition(
            ready,
            ModelProfilesIntent.Public.Save(stored.copy(summary = "x".repeat(ProfileLimits.MAX_SUMMARY + 1)), 1),
            ready,
            outputs = listOf(ModelProfilesOutput.Rejected(key, SaveRejection.Invalid)),
        )
        val full = ModelProfilesState.Ready(
            List(ProfileLimits.MAX_PROFILES) { stored.copy(key = ModelKey(EngineId("e"), ModelId("m$it"))) },
        )
        spec.assertTransition(
            full,
            ModelProfilesIntent.Public.Save(profile, null),
            full,
            outputs = listOf(ModelProfilesOutput.Rejected(key, SaveRejection.Limit)),
        )
    }

    @Test
    fun `delete and preferences change the library and persist`() {
        val ready = ModelProfilesState.Ready(listOf(stored), revision = 2)
        spec.assertTransition(
            ready,
            ModelProfilesIntent.Public.Delete(key),
            ModelProfilesState.Ready(revision = 3),
            effects = listOf(ModelProfilesEffect.Persist(emptyList(), SubagentPreferences(), 3)),
        )
        spec.assertIgnored(ready, ModelProfilesIntent.Public.Delete(other))
        spec.assertTransition(
            ready,
            ModelProfilesIntent.Public.SetPreferences(preferences),
            ModelProfilesState.Ready(listOf(stored), preferences, revision = 3),
            effects = listOf(ModelProfilesEffect.Persist(listOf(stored), preferences, 3)),
        )
        spec.assertIgnored(ready, ModelProfilesIntent.Public.SetPreferences(SubagentPreferences()))
    }

    @Test
    fun `a failed save is reported and the library kept`() {
        val ready = ModelProfilesState.Ready(listOf(stored), revision = 2)
        spec.assertTransition(
            ready,
            ModelProfilesIntent.Internal.SaveFailed,
            ready,
            outputs = listOf(ModelProfilesOutput.StorageFailed),
        )
    }

    @Test
    fun `changes before loading are ignored`() {
        for (state in listOf(ModelProfilesState.Idle, ModelProfilesState.Loading)) {
            spec.assertIgnored(state, ModelProfilesIntent.Public.Save(profile, null))
            spec.assertIgnored(state, ModelProfilesIntent.Public.Delete(key))
            spec.assertIgnored(state, ModelProfilesIntent.Public.SetPreferences(preferences))
        }
    }
}
