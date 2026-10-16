package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection

import io.aequicor.heartbeat.feature.adaptivesubagents.api.ConnectionCost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.CostMode
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ProfileVariant
import io.aequicor.heartbeat.feature.adaptivesubagents.api.RatingSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentComplexity
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentHost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentNeed
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Traits
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ScoringTest {
    private val opus = candidate("claude", "api", "claude-opus-4-1", efforts = listOf("low", "medium", "high"))
    private val haiku = candidate("claude", "api", "claude-haiku-4-5")
    private val local = candidate("koog", "ollama", "qwen3-coder")
    private val context = SelectionContext(SubagentHost.StudioHelper, opus.target, null)
    private val prefs = SubagentPreferences()

    @Test
    fun `speed priority prefers a small model and quality on a hard task prefers a large one`() {
        val pool = expandAll(opus) + haiku
        val fast = shortlist(pool, SubagentRequest("t", SubagentPriority.Speed), context, emptyList(), prefs)
        assertEquals(haiku.target, fast.first().candidate.target)
        val hard = SubagentRequest("t", SubagentPriority.Quality, SubagentComplexity.High)
        val best = shortlist(pool, hard, context, emptyList(), prefs).first()
        assertEquals(opus.target, best.candidate.target)
        assertEquals("high", best.candidate.effort)
    }

    @Test
    fun `a description overrides the name baseline and is preferred to a guess`() {
        val described = ModelProfile(
            ModelKey.of(haiku.target),
            "Haiku",
            base = Traits(Ratings(5, 5, 5)),
            updatedAt = Instant.fromEpochSeconds(1),
        )
        val rated = rate(haiku, listOf(described), prefs)
        assertEquals(Ratings(5, 5, 5), rated.ratings)
        assertEquals(RatingSource.Described, rated.basis)
        assertEquals(RatingSource.Heuristic, rate(opus, listOf(described), prefs).basis)
    }

    @Test
    fun `an effort variant is used as described and others are derived from the base`() {
        val profile = ModelProfile(
            ModelKey.of(opus.target),
            "Opus",
            base = Traits(Ratings(5, 2, 1)),
            variants = listOf(ProfileVariant("low", Traits(Ratings(4, 5, 3)))),
            updatedAt = Instant.fromEpochSeconds(1),
        )
        val described = opus.copy(defaultEffort = "medium")
        assertEquals(Ratings(4, 5, 3), rate(described.copy(effort = "low"), listOf(profile), prefs).ratings)
        assertEquals(Ratings(5, 1, 1), rate(described.copy(effort = "high"), listOf(profile), prefs).ratings)
    }

    @Test
    fun `the same model on another engine lends its description`() {
        val pi = candidate("pi", "anthropic", "anthropic/claude-haiku-4-5-20251001")
        val profile = ModelProfile(
            ModelKey.of(haiku.target),
            "Haiku",
            tags = setOf("quick-edits"),
            base = Traits(Ratings(4, 5, 4)),
            updatedAt = Instant.fromEpochSeconds(1),
        )
        val rated = rate(pi, listOf(profile), prefs)
        assertEquals(RatingSource.Borrowed, rated.basis)
        assertEquals(Ratings(4, 5, 4), rated.ratings)
        assertTrue("quick-edits" in rated.tags)
    }

    @Test
    fun `local and subscription billing make a connection cheap`() {
        val billed = prefs.copy(
            connectionCosts = listOf(
                ConnectionCost(EngineBindingId("ollama"), CostMode.Local),
                ConnectionCost(EngineBindingId("api"), CostMode.Subscription),
            ),
        )
        assertEquals(Ratings.MAX, rate(local, emptyList(), billed).ratings.cost)
        assertEquals(4, rate(opus.copy(effort = "medium"), emptyList(), billed).ratings.cost)
        val cheapest = shortlist(
            listOf(opus.copy(effort = "medium"), local),
            SubagentRequest("t", SubagentPriority.Cost, SubagentComplexity.Low),
            context,
            emptyList(),
            billed,
        )
        assertEquals(local.target, cheapest.first().candidate.target)
    }

    @Test
    fun `matching task tags lift a candidate`() {
        val request = SubagentRequest("t", tags = setOf("coding"))
        val withTag = shortlist(listOf(local), request, context, emptyList(), prefs).single()
        val without = shortlist(listOf(local), request.copy(tags = emptySet()), context, emptyList(), prefs).single()
        assertEquals(setOf("coding"), withTag.matchedTags)
        assertTrue(withTag.score > without.score)
    }

    @Test
    fun `the shortlist keeps three configurations and two per model route`() {
        val many = expandAll(opus.copy(efforts = listOf("minimal", "low", "medium", "high"))) + haiku + local
        val list = shortlist(many, SubagentRequest("t"), context, emptyList(), prefs)
        assertEquals(3, list.size)
        assertTrue(list.groupBy { it.candidate.target }.values.all { it.size <= 2 })
        assertEquals(list.sortedByDescending { it.score }, list)
    }

    @Test
    fun `required inputs capabilities exclusions and engines filter candidates`() {
        val request = SubagentRequest("t")
        val images = request.copy(needs = setOf(SubagentNeed.Images))
        assertFalse(isEligible(haiku, images, context, prefs))
        assertTrue(isEligible(haiku.copy(hasImageInput = true), images, context, prefs))
        val long = request.copy(needs = setOf(SubagentNeed.LongContext))
        assertFalse(isEligible(haiku.copy(contextTokens = 100_000), long, context, prefs))
        assertTrue(isEligible(haiku, long, context, prefs))
        assertFalse(isEligible(haiku, request, context, prefs.copy(excluded = setOf(haiku.target))))
        assertFalse(isEligible(local, request, context, prefs.copy(isCrossEngineAllowed = false)))
        val noTrust = haiku.copy(abilities = EngineAbilities(isTrustApplied = false))
        assertFalse(isEligible(noTrust, request, context, prefs))
        assertTrue(isEligible(noTrust, request, context.copy(host = SubagentHost.OrganismCell), prefs))
        val noWorkspace = haiku.copy(abilities = EngineAbilities(isWorkspaceSupported = false))
        assertFalse(isEligible(noWorkspace, request, context.copy(workspace = WorkspaceRef("p")), prefs))
        val noResume = haiku.copy(abilities = EngineAbilities(isResumable = false))
        assertFalse(isEligible(noResume, request, context.copy(host = SubagentHost.OrganismCell), prefs))
    }

    @Test
    fun `a forbidden engine change requires a known matching parent`() {
        val request = SubagentRequest("t")
        val restricted = prefs.copy(isCrossEngineAllowed = false)
        assertTrue(isEligible(haiku, request, context, restricted))
        assertFalse(isEligible(local, request, context, restricted))
        assertFalse(isEligible(haiku, request, context.copy(parentTarget = null), restricted))
        assertTrue(isEligible(haiku, request, context.copy(parentTarget = null), prefs))
    }

    private fun expandAll(template: Candidate) =
        expand(template.target, template.title, template.efforts, template.defaultEffort, template)

    private fun candidate(engine: String, binding: String, model: String, efforts: List<String> = emptyList()) =
        Candidate(
            EngineTarget(EngineId(engine), EngineBindingId(binding), ModelId(model)),
            effort = null,
            title = model,
            efforts = efforts,
            defaultEffort = efforts.getOrNull(efforts.size / 2),
        )
}
