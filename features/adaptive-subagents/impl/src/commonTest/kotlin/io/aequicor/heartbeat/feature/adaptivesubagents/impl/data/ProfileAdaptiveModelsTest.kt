package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data

import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsEnabled
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsRouter
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
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Traits
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakeCatalogFacade
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakeLibrary
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakePrompts
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakeSelections
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakeToggles
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.router.ShortlistRouter
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.target
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /** Two unknown models score alike, so the router decides. */
    private val twins = listOf(
        ModelInfo(target("claude", "api", "alpha"), "Alpha"),
        ModelInfo(target("claude", "api", "beta"), "Beta"),
    )

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
        val toggles = FakeToggles(mapOf(AdaptiveSubagentsEnabled to true))
        val models = ProfileAdaptiveModels(
            CatalogCandidates(lazyOf(facade), lazyOf(FakeSelections(local))),
            ShortlistRouter(lazyOf(FakePrompts()), toggles),
            lazyOf(FakeLibrary()),
            toggles,
        )
        assertEquals(ModelChoice.Inherit(InheritReason.NoCandidates), models.select(SubagentRequest("t"), context))
        val cell = context.copy(host = SubagentHost.OrganismCell)
        assertEquals(ModelChoice.Inherit(InheritReason.NoCandidates), models.select(SubagentRequest("t"), cell))
    }

    @Test
    fun `a close shortlist goes to the router on the profile's default model`() = runTest {
        val prompts = FakePrompts(IsolatedAnswer.Answered("""{"choice":"c2","rationale":"Needs care"}"""))
        val models = models(router = true, prompts = prompts, catalog = twins)
        val balanced = SubagentRequest("Review this diff")
        val options = models.preview(balanced, context)
        val selected = assertIs<ModelChoice.Selected>(models.select(balanced, context))
        assertEquals(ChoiceSource.Router, selected.source)
        assertEquals(options[1].target to options[1].reasoningEffort, selected.target to selected.reasoningEffort)
        assertTrue(selected.rationale.startsWith("Needs care"), selected.rationale)
        assertEquals(target("claude", "api", "alpha"), prompts.asked.single().target)
        assertTrue("Review this diff" in prompts.asked.single().text)
    }

    @Test
    fun `a failed router falls back to the best scored configuration`() = runTest {
        val prompts = FakePrompts(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout))
        val request = SubagentRequest("t")
        val best = models(catalog = twins).preview(request, context).first()
        val routed = models(router = true, prompts = prompts, catalog = twins)
        val selected = assertIs<ModelChoice.Selected>(routed.select(request, context))
        assertEquals(ChoiceSource.RouterFallback, selected.source)
        assertEquals(best.target, selected.target)
    }

    @Test
    fun `catalog failures preserve inheritance and leave the preview empty`() = runTest {
        val selections = object : ModelSelections by FakeSelections(opus) {
            override fun observe() = flow<ModelSelection> { error("storage unavailable") }
        }
        val models = models(selections = selections)
        val request = SubagentRequest("t")
        assertEquals(ModelChoice.Inherit(InheritReason.Unavailable), models.select(request, context))
        assertTrue(models.preview(request, context).isEmpty())
    }

    @Test
    fun `thrown router failures retain the best scored configuration`() = runTest {
        val prompts = IsolatedPrompts { error("provider unavailable") }
        val request = SubagentRequest("t")
        val best = models(catalog = twins).preview(request, context).first()
        val routed = models(router = true, prompts = prompts, catalog = twins)
        val selected = assertIs<ModelChoice.Selected>(routed.select(request, context))
        assertEquals(ChoiceSource.RouterFallback, selected.source)
        assertEquals(best.target, selected.target)
    }

    @Test
    fun `failure reading the router default keeps the scored shortlist`() = runTest {
        val selection = FakeSelections(*twins.map { it.target }.toTypedArray())
        var reads = 0
        val selections = object : ModelSelections by selection {
            override fun observe() = if (++reads == 1) {
                selection.observe()
            } else {
                flow<ModelSelection> { error("unreadable") }
            }
        }
        val selected = assertIs<ModelChoice.Selected>(
            models(router = true, catalog = twins, selections = selections).select(SubagentRequest("t"), context),
        )
        assertEquals(ChoiceSource.RouterFallback, selected.source)
        assertEquals(twins.first().target, selected.target)
    }

    @Test
    fun `catalog and router cancellation propagate to the caller`() = runTest {
        val selections = object : ModelSelections by FakeSelections(opus) {
            override fun observe() = flow<ModelSelection> { throw CancellationException("cancelled") }
        }
        val catalogFailure = models(selections = selections)
        assertFailsWith<CancellationException> { catalogFailure.select(SubagentRequest("t"), context) }
        assertFailsWith<CancellationException> { catalogFailure.preview(SubagentRequest("t"), context) }
        val routerFailure = models(
            router = true,
            catalog = twins,
            prompts = IsolatedPrompts { throw CancellationException("cancelled") },
        )
        assertFailsWith<CancellationException> { routerFailure.select(SubagentRequest("t"), context) }
    }

    @Test
    fun `unknown harness parent inherits when cross engine selection is forbidden`() = runTest {
        val library = ModelProfilesState.Ready(preferences = SubagentPreferences(isCrossEngineAllowed = false))
        val models = models(library = library, router = true)
        for (host in listOf(SubagentHost.WorkflowHelper, SubagentHost.ScriptHelper)) {
            val unknown = context.copy(host = host, parentTarget = null)
            assertEquals(ModelChoice.Inherit(InheritReason.NoCandidates), models.select(SubagentRequest("t"), unknown))
            assertTrue(models.preview(SubagentRequest("t"), unknown).isEmpty())
        }
    }

    private fun models(
        enabled: Boolean = true,
        library: ModelProfilesState = ModelProfilesState.Ready(),
        router: Boolean = false,
        prompts: IsolatedPrompts = FakePrompts(),
        catalog: List<ModelInfo> = this.catalog,
        selections: ModelSelections? = null,
    ): ProfileAdaptiveModels {
        val toggles = FakeToggles(mapOf(AdaptiveSubagentsEnabled to enabled, AdaptiveSubagentsRouter to router))
        val enabledModels = catalog.map { it.target }.filter { it != hidden }
        val selected = selections ?: FakeSelections(*enabledModels.toTypedArray(), default = enabledModels.first())
        return ProfileAdaptiveModels(
            CatalogCandidates(lazyOf(FakeCatalogFacade(catalog)), lazyOf(selected)),
            ShortlistRouter(lazyOf(prompts), toggles),
            lazyOf(FakeLibrary(library)),
            toggles,
        )
    }
}
