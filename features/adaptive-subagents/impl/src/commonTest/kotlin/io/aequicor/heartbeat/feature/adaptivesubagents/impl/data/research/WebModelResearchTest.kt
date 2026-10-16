package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.research

import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelFacts
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchFailure
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchJob
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchStage
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchSubject
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakeCatalogFacade
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakePrompts
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.ResearchOutcome
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.target
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class WebModelResearchTest {
    private val researcher = target("claude", "api", "claude-opus-4-1")
    private val subject = ResearchSubject(ModelKey.of(target("codex", "cx", "gpt-5")), "GPT-5")
    private val job = ResearchJob("j1", subject, researcher)
    private val valid = """{"summary":"Код","base":{"quality":5,"speed":2,"cost":3},"sources":[0]}"""
    private val facts = ModelFacts(
        contextTokens = 400_000,
        source = "models.dev",
        fetchedAt = Instant.fromEpochSeconds(1),
    )

    @Test
    fun `found pages and facts are synthesized into a description after one repair`() = runTest {
        val prompts = FakePrompts(IsolatedAnswer.Answered(valid)).apply { next += IsolatedAnswer.Answered("nope") }
        val stages = mutableListOf<Pair<ResearchStage, Int>>()
        val outcome = research(prompts, WebSearch()).research(job) { stage, n -> stages += stage to n }
        val profile = assertIs<ResearchOutcome.Written>(outcome).profile
        assertEquals(listOf("https://openai.com/gpt-5"), profile.sources)
        assertEquals(facts, profile.facts)
        assertEquals(2, prompts.asked.size)
        assertTrue("rejected" in prompts.asked.last().text)
        assertEquals(researcher, prompts.asked.first().target)
        assertTrue("Page about gpt-5" in prompts.asked.first().text)
        assertEquals(
            listOf(ResearchStage.Facts, ResearchStage.Searching, ResearchStage.Reading, ResearchStage.Writing),
            stages.map { it.first }.distinct(),
        )
    }

    @Test
    fun `an unconfigured search still writes from catalog facts`() = runTest {
        val outcome = research(FakePrompts(IsolatedAnswer.Answered(valid)), WebSearch(isConfigured = false))
            .research(job) { _, _ -> }
        assertIs<ResearchOutcome.Written>(outcome)
    }

    @Test
    fun `nothing to read fails without asking the researcher`() = runTest {
        val prompts = FakePrompts()
        val outcome = research(prompts, WebSearch(isConfigured = false), known = null).research(job) { _, _ -> }
        assertEquals(ResearchOutcome.Failed(ResearchFailure.NoSources), outcome)
        assertTrue(prompts.asked.isEmpty())
    }

    @Test
    fun `an unavailable researcher or two invalid answers fail the run`() = runTest {
        val down = FakePrompts(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout))
        assertEquals(
            ResearchOutcome.Failed(ResearchFailure.ResearcherUnavailable),
            research(down, WebSearch()).research(job) { _, _ -> },
        )
        val confused = FakePrompts(IsolatedAnswer.Answered("still not json"))
        assertEquals(
            ResearchOutcome.Failed(ResearchFailure.InvalidAnswer),
            research(confused, WebSearch()).research(job) { _, _ -> },
        )
        assertEquals(2, confused.asked.size)
    }

    private fun research(prompts: FakePrompts, search: SearchEngine, known: ModelFacts? = facts) = WebModelResearch(
        { known },
        lazyOf(search),
        lazyOf(FakeCatalogFacade(listOf(ModelInfo(researcher, "Opus")))),
        lazyOf(prompts),
        Clock.System,
    )

    private class WebSearch(private val isConfigured: Boolean = true) : SearchEngine {
        override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
            if (!isConfigured) throw SearchException(SearchFailure.NotConfigured)
            return listOf(SearchResult("https://openai.com/gpt-5", "GPT-5", "snippet"))
        }

        override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent =
            ResourceContent(url, "GPT-5", "Page about gpt-5")
    }
}
