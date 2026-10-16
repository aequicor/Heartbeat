package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection

import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals

class HeuristicsTest {
    @Test
    fun `model names place undescribed models into small middle or large families`() {
        val small = Ratings(3, 5, 5)
        val middle = Ratings(4, 3, 3)
        val large = Ratings(5, 2, 2)
        val expected = mapOf(
            "claude-haiku-4-5" to small,
            "gpt-5-mini" to small,
            "o3-mini" to small,
            "llama3.1-8b" to small,
            "claude-opus-4-1" to large,
            "gpt-5" to large,
            "gpt-5.1-codex" to large,
            "o3" to large,
            "gemini-2.5-pro" to large,
            "claude-sonnet-4-5" to middle,
            "gemini-2.5-flash" to middle,
            "something-new" to Ratings.NEUTRAL,
        )
        for ((model, ratings) in expected) assertEquals(ratings, baselineRatings(ModelId(model), ""), model)
        assertEquals(setOf("coding"), baselineTags(ModelId("gpt-5.1-codex"), ""))
    }

    @Test
    fun `efforts are ranked on a common scale`() {
        assertEquals(0, effortRank("minimal", emptyList()))
        assertEquals(1, effortRank("LOW", emptyList()))
        assertEquals(3, effortRank("high", emptyList()))
        assertEquals(4, effortRank("xhigh", emptyList()))
        assertEquals(2, effortRank(null, emptyList()))
        assertEquals(0, effortRank("think-a", listOf("think-a", "think-b", "think-c")))
        assertEquals(2, effortRank("think-b", listOf("think-a", "think-b", "think-c")))
        assertEquals(4, effortRank("think-c", listOf("think-a", "think-b", "think-c")))
    }

    @Test
    fun `ratings move with effort relative to the described default`() {
        val medium = Ratings(4, 3, 3)
        assertEquals(RawRatings(3, 4, 4), medium.atEffort("low", "medium", emptyList()))
        assertEquals(RawRatings(5, 2, 2), medium.atEffort("high", "medium", emptyList()))
        assertEquals(RawRatings(4, 3, 3), Ratings(5, 2, 2).atEffort("medium", "high", emptyList()))
        val top = Ratings(5, 1, 1).atEffort("max", "medium", emptyList())
        assertEquals(RawRatings(6, 0, 0), top)
        assertEquals(Ratings(5, 1, 1), top.clamped())
    }

    @Test
    fun `model ids compare across engines without prefixes tags or dates`() {
        assertEquals("claude-sonnet-4-5", normalizedModel(ModelId("anthropic/claude-sonnet-4-5-20250929")))
        assertEquals("qwen3", normalizedModel(ModelId("qwen3:latest")))
        assertEquals("gpt-5", normalizedModel(ModelId("GPT-5")))
    }
}
