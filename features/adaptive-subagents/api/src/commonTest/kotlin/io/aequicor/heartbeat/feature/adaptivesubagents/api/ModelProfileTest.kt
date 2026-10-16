package io.aequicor.heartbeat.feature.adaptivesubagents.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class ModelProfileTest {
    private val profile = ModelProfile(
        ModelKey(EngineId("codex"), ModelId("gpt-5")),
        "GPT-5",
        "Line one\nline two",
        tags = setOf("coding", "quick-edits"),
        base = Traits(Ratings(5, 2, 2), strengths = listOf("Deep reasoning")),
        variants = listOf(ProfileVariant("low", Traits(Ratings(4, 4, 3)))),
        sources = listOf("https://example.com/model"),
        updatedAt = Instant.fromEpochSeconds(1),
    )

    @Test
    fun `a valid description has no problem and survives storage`() {
        assertNull(profile.problem())
        assertEquals(profile, Json.decodeFromString<ModelProfile>(Json.encodeToString(profile)))
    }

    @Test
    fun `traits of an effort come from its variant or the base`() {
        assertEquals(Ratings(4, 4, 3), profile.traitsFor("low").ratings)
        assertEquals(Ratings(5, 2, 2), profile.traitsFor("high").ratings)
        assertEquals(Ratings(5, 2, 2), profile.traitsFor(null).ratings)
    }

    @Test
    fun `normalization trims texts and drops blank or repeated items`() {
        val messy = profile.copy(
            title = " GPT-5 ",
            tags = setOf(" Coding ", ""),
            base = Traits(strengths = listOf(" a ", "a", " ")),
            sources = listOf(" https://x.dev ", "https://x.dev"),
        )
        val clean = messy.normalized()
        assertEquals("GPT-5", clean.title)
        assertEquals(setOf("coding"), clean.tags)
        assertEquals(listOf("a"), clean.base.strengths)
        assertEquals(listOf("https://x.dev"), clean.sources)
    }

    @Test
    fun `problems are found in limits tags variants sources and hidden text`() {
        assertEquals(ProfileProblem.TooLong, profile.copy(title = "t".repeat(81)).problem())
        assertEquals(
            ProfileProblem.TooMany,
            profile.copy(base = Traits(useCases = List(ProfileLimits.MAX_ITEMS + 1) { "u$it" })).problem(),
        )
        assertEquals(ProfileProblem.InvalidTag, profile.copy(tags = setOf("Bad Tag")).problem())
        val twice = ProfileVariant("low", Traits())
        assertEquals(ProfileProblem.DuplicateVariant, profile.copy(variants = listOf(twice, twice)).problem())
        assertEquals(ProfileProblem.InvalidSource, profile.copy(sources = listOf("file:///etc/passwd")).problem())
        assertEquals(ProfileProblem.InvisibleText, profile.copy(summary = "ok​hidden").problem())
        assertEquals(ProfileProblem.InvisibleText, profile.copy(title = "tab\there").problem())
    }

    @Test
    fun `ratings stay within one to five`() {
        assertFailsWith<IllegalArgumentException> { Ratings(0, 3, 3) }
        assertFailsWith<IllegalArgumentException> { Ratings(3, 6, 3) }
    }

    @Test
    fun `connection billing defaults to metered`() {
        val preferences = SubagentPreferences(
            connectionCosts = listOf(ConnectionCost(EngineBindingId("local"), CostMode.Local)),
        )
        assertEquals(CostMode.Local, preferences.costOf(EngineBindingId("local")))
        assertEquals(CostMode.Metered, preferences.costOf(EngineBindingId("api")))
    }
}
