package io.aequicor.heartbeat.feature.adaptivesubagents.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.test.Test
import kotlin.time.Instant

class ModelResearchMachineTest {
    private val spec = ModelProfilesMachineSpec
    private val key = ModelKey(EngineId("codex"), ModelId("gpt-5"))
    private val other = ModelKey(EngineId("claude"), ModelId("sonnet"))
    private val researcher = EngineTarget(EngineId("claude"), EngineBindingId("api"), ModelId("opus"))
    private val subject = ResearchSubject(key, "GPT-5", listOf("low", "high"), "high")
    private val job = ResearchJob("j1", subject, researcher)
    private val written = ModelProfile(key, "GPT-5", "Strong", updatedAt = Instant.fromEpochSeconds(5))

    @Test
    fun `a research run starts for an idle model and is limited`() {
        spec.assertTransition(
            ModelProfilesState.Ready(),
            ModelProfilesIntent.Public.Research("j1", subject, researcher),
            ModelProfilesState.Ready(research = listOf(job)),
            effects = listOf(ModelProfilesEffect.Research(job)),
        )
        val busy = ModelProfilesState.Ready(research = listOf(job))
        spec.assertTransition(
            busy,
            ModelProfilesIntent.Public.Research("j2", subject, researcher),
            busy,
            outputs = listOf(ModelProfilesOutput.ResearchFinished(key, ResearchFailure.Busy)),
        )
        val full = ModelProfilesState.Ready(
            research = List(ResearchLimits.MAX_JOBS) { job.copy(id = "x$it", subject = subject.copy(key = other)) },
        )
        spec.assertTransition(
            full,
            ModelProfilesIntent.Public.Research("j3", subject, researcher),
            full,
            outputs = listOf(ModelProfilesOutput.ResearchFinished(key, ResearchFailure.Busy)),
        )
    }

    @Test
    fun `progress updates the running job only`() {
        val running = ModelProfilesState.Ready(research = listOf(job))
        spec.assertTransition(
            running,
            ModelProfilesIntent.Internal.ResearchProgress("j1", ResearchStage.Reading, 3),
            ModelProfilesState.Ready(research = listOf(job.copy(stage = ResearchStage.Reading, sources = 3))),
        )
        spec.assertIgnored(running, ModelProfilesIntent.Internal.ResearchProgress("gone", ResearchStage.Writing, 1))
    }

    @Test
    fun `a written description is saved as researched and persisted`() {
        val running = ModelProfilesState.Ready(research = listOf(job), revision = 4)
        val saved = written.copy(source = ProfileSource.Research, revision = 1)
        spec.assertTransition(
            running,
            ModelProfilesIntent.Internal.ResearchCompleted("j1", written),
            ModelProfilesState.Ready(listOf(saved), revision = 5),
            effects = listOf(ModelProfilesEffect.Persist(listOf(saved), SubagentPreferences(), 5)),
            outputs = listOf(ModelProfilesOutput.ResearchFinished(key, null)),
        )
    }

    @Test
    fun `an invalid or foreign description ends the job without saving`() {
        val running = ModelProfilesState.Ready(research = listOf(job))
        spec.assertTransition(
            running,
            ModelProfilesIntent.Internal.ResearchCompleted("j1", written.copy(tags = setOf("Bad Tag"))),
            ModelProfilesState.Ready(),
            outputs = listOf(ModelProfilesOutput.ResearchFinished(key, ResearchFailure.InvalidAnswer)),
        )
        spec.assertTransition(
            running,
            ModelProfilesIntent.Internal.ResearchCompleted("j1", written.copy(key = other)),
            ModelProfilesState.Ready(),
            outputs = listOf(ModelProfilesOutput.ResearchFinished(other, ResearchFailure.InvalidAnswer)),
        )
    }

    @Test
    fun `failure and cancellation end the job and late results are ignored`() {
        val running = ModelProfilesState.Ready(research = listOf(job))
        spec.assertTransition(
            running,
            ModelProfilesIntent.Internal.ResearchFailed("j1", ResearchFailure.NoSources),
            ModelProfilesState.Ready(),
            outputs = listOf(ModelProfilesOutput.ResearchFinished(key, ResearchFailure.NoSources)),
        )
        spec.assertTransition(
            running,
            ModelProfilesIntent.Public.CancelResearch(key),
            ModelProfilesState.Ready(),
            effects = listOf(ModelProfilesEffect.StopResearch("j1")),
            outputs = listOf(ModelProfilesOutput.ResearchFinished(key, ResearchFailure.Cancelled)),
        )
        val idle = ModelProfilesState.Ready()
        spec.assertIgnored(idle, ModelProfilesIntent.Internal.ResearchCompleted("j1", written))
        spec.assertIgnored(idle, ModelProfilesIntent.Internal.ResearchFailed("j1", ResearchFailure.Failed))
        spec.assertIgnored(idle, ModelProfilesIntent.Public.CancelResearch(key))
    }

    @Test
    fun `saving by hand cancels only that models research and ignores its late result`() {
        val otherJob = job.copy(id = "j2", subject = subject.copy(key = other))
        val running = ModelProfilesState.Ready(research = listOf(job, otherJob))
        val manual = written.copy(summary = "Written by hand", revision = 1)
        val saved = ModelProfilesState.Ready(listOf(manual), research = listOf(otherJob), revision = 1)
        spec.assertTransition(
            running,
            ModelProfilesIntent.Public.Save(manual, expectedRevision = null),
            saved,
            effects = listOf(
                ModelProfilesEffect.Persist(listOf(manual), SubagentPreferences(), 1),
                ModelProfilesEffect.StopResearch(job.id),
            ),
        )
        spec.assertIgnored(saved, ModelProfilesIntent.Internal.ResearchCompleted(job.id, written))
        spec.assertIgnored(saved, ModelProfilesIntent.Internal.ResearchProgress(job.id, ResearchStage.Writing, 1))
        spec.assertIgnored(saved, ModelProfilesIntent.Internal.ResearchFailed(job.id, ResearchFailure.Failed))
    }

    @Test
    fun `deleting a description cancels its research without recreating it later`() {
        val stored = written.copy(revision = 1)
        val running = ModelProfilesState.Ready(listOf(stored), research = listOf(job), revision = 1)
        val deleted = ModelProfilesState.Ready(revision = 2)
        spec.assertTransition(
            running,
            ModelProfilesIntent.Public.Delete(key),
            deleted,
            effects = listOf(
                ModelProfilesEffect.Persist(emptyList(), SubagentPreferences(), 2),
                ModelProfilesEffect.StopResearch(job.id),
            ),
        )
        spec.assertIgnored(deleted, ModelProfilesIntent.Internal.ResearchCompleted(job.id, written))
    }

    @Test
    fun `a rejected manual save leaves research running`() {
        val stored = written.copy(revision = 1)
        val running = ModelProfilesState.Ready(listOf(stored), research = listOf(job))
        spec.assertTransition(
            running,
            ModelProfilesIntent.Public.Save(stored, expectedRevision = null),
            running,
            outputs = listOf(ModelProfilesOutput.Rejected(key, SaveRejection.Conflict)),
        )
        spec.assertTransition(
            running,
            ModelProfilesIntent.Public.Save(stored.copy(tags = setOf("Invalid tag")), expectedRevision = 1),
            running,
            outputs = listOf(ModelProfilesOutput.Rejected(key, SaveRejection.Invalid)),
        )
    }
}
