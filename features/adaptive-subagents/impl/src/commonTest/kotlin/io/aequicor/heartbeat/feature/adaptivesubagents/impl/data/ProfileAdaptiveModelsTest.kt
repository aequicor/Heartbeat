package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data

import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsEnabled
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ChoiceSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.InheritReason
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelChoice
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.RatingSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentHost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentNeed
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Traits
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakeCatalogFacade
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakeLibrary
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakeSelections
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakeToggles
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.target
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProfileAdaptiveModelsTest {
    private val opus = target("claude", "api", "claude-opus-4-1")
    private val haiku = target("claude", "api", "claude-haiku-4-5")
    private val hidden = target("claude", "api", "claude-sonnet-4-5")
    private val catalog = listOf(
        ModelInfo(opus, "Opus", reasoningEfforts = listOf("low", "high"), defaultReasoningEffort = "high"),
        ModelInfo(haiku, "Haiku", inputSupport = PromptInputSupport(imageMediaTypes = setOf("image/png"))),
        ModelInfo(hidden, "Sonnet"),
    )
    private val context = SelectionContext(SubagentHost.StudioHelper, opus, null)

    @Test
    fun `turned off selection keeps the inherited model`() = runTest {
        val models = models(enabled = false)
        assertEquals(ModelChoice.Inherit(InheritReason.Disabled), models.select(SubagentRequest("t"), context))
        assertTrue(models.preview(SubagentRequest("t"), context).isEmpty())
    }

    @Test
    fun `only models the user enabled are offered and the best one is chosen with a rationale`() = runTest {
        val choice = models().select(SubagentRequest("Fix typos", SubagentPriority.Speed), context)
        val selected = assertIs<ModelChoice.Selected>(choice)
        assertEquals(haiku, selected.target)
        assertEquals(ChoiceSource.Scoring, selected.source)
        assertTrue("Haiku" in selected.rationale && "priority speed" in selected.rationale, selected.rationale)
        val offered = models().preview(SubagentRequest("t"), context).map { it.target }.toSet()
        assertEquals(setOf(opus, haiku), offered)
    }

    @Test
    fun `efforts of a model become separate configurations`() = runTest {
        val hard = SubagentRequest("Prove it", SubagentPriority.Quality, needs = emptySet())
        val selected = assertIs<ModelChoice.Selected>(models().select(hard, context))
        assertEquals(opus, selected.target)
        assertEquals("high", selected.reasoningEffort)
    }

    @Test
    fun `descriptions from the library decide over name estimates`() = runTest {
        val described = ModelProfile(
            ModelKey.of(opus),
            "Opus",
            base = Traits(Ratings(5, 5, 5)),
            updatedAt = Instant.fromEpochSeconds(1),
        )
        val models = models(library = ModelProfilesState.Ready(listOf(described)))
        val options = models.preview(SubagentRequest("t", SubagentPriority.Speed), context)
        assertEquals(opus, options.first().target)
        assertEquals(RatingSource.Described, options.first().ratingSource)
    }

    @Test
    fun `no fitting model inherits and an unreadable library falls back to estimates`() = runTest {
        val needsPdf = SubagentRequest("t", needs = setOf(SubagentNeed.Pdf))
        assertEquals(ModelChoice.Inherit(InheritReason.NoCandidates), models().select(needsPdf, context))
        val failed = models(library = ModelProfilesState.Failed)
        assertIs<ModelChoice.Selected>(failed.select(SubagentRequest("t"), context))
    }

    @Test
    fun `engines that cannot apply trust are skipped for helpers but serve organism cells`() = runTest {
        val local = target("koog", "ollama", "qwen3")
        val facade = FakeCatalogFacade(listOf(ModelInfo(local, "Qwen")), limited = setOf(EngineId("koog")))
        val models = ProfileAdaptiveModels(
            CatalogCandidates(lazyOf(facade), lazyOf(FakeSelections(local))),
            lazyOf(FakeLibrary()),
            FakeToggles(mapOf(AdaptiveSubagentsEnabled to true)),
        )
        assertEquals(ModelChoice.Inherit(InheritReason.NoCandidates), models.select(SubagentRequest("t"), context))
        val cell = context.copy(host = SubagentHost.OrganismCell)
        assertEquals(ModelChoice.Inherit(InheritReason.NoCandidates), models.select(SubagentRequest("t"), cell))
    }

    private fun models(enabled: Boolean = true, library: ModelProfilesState = ModelProfilesState.Ready()) =
        ProfileAdaptiveModels(
            CatalogCandidates(lazyOf(FakeCatalogFacade(catalog)), lazyOf(FakeSelections(opus, haiku))),
            lazyOf(FakeLibrary(library)),
            FakeToggles(mapOf(AdaptiveSubagentsEnabled to enabled)),
        )
}
