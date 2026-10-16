package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.research

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelFacts
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchFailure
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchJob
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchStage
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.ModelResearch
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.ParsedDescription
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.ResearchOutcome
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.SourcePage
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.parseDescription
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.pickPages
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.repairPrompt
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.researchQueries
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.synthesisPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.Fence
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.cut
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The research pipeline run by the host: catalog facts, web search and page reading through the profile's search
 * provider (or the researcher engine's native search), then one tool-less synthesis prompt on the researcher model,
 * repaired once when its answer is not a valid description. Queries, pages, prompts and answers are never logged.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class WebModelResearch(
    private val facts: ModelFactsSource,
    private val search: Lazy<SearchEngine>,
    private val facade: Lazy<EngineFacade>,
    private val prompts: Lazy<IsolatedPrompts>,
    private val clock: Clock,
) : ModelResearch {
    private val log = Log.tag("ModelResearch")

    override suspend fun research(job: ResearchJob, progress: suspend (ResearchStage, Int) -> Unit): ResearchOutcome {
        progress(ResearchStage.Facts, 0)
        val known = facts.facts(job.subject.key.model)
        progress(ResearchStage.Searching, 0)
        val native = facade.value.engines.features(job.researcher.engine)
        val urls = pickPages(researchQueries(job.subject).flatMap { find(it, native) })
        val pages = read(urls, native, progress)
        log.i { "research ${job.id}: facts=${known != null} found=${urls.size} read=${pages.size}" }
        if (known == null && pages.isEmpty()) return ResearchOutcome.Failed(ResearchFailure.NoSources)
        progress(ResearchStage.Writing, pages.size)
        return write(job, known, pages)
    }

    private suspend fun find(query: String, native: EngineFeatures): List<String> = try {
        withTimeoutOrNull(SEARCH_TIMEOUT) { search.value.search(query, RESULTS, native) }.orEmpty().map { it.url }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SearchException) {
        if (e.failure != SearchFailure.NotConfigured) log.w { "research search failed: ${e.failure}" }
        emptyList()
    } catch (e: Exception) {
        log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "research search failed" }
        emptyList()
    }

    private suspend fun read(
        urls: List<String>,
        native: EngineFeatures,
        progress: suspend (ResearchStage, Int) -> Unit,
    ): List<SourcePage> {
        val pages = mutableListOf<SourcePage>()
        var budget = MAX_TOTAL
        for (url in urls) {
            if (budget > 0) {
                fetch(url, native)?.takeIf(String::isNotBlank)?.let { text ->
                    val excerpt = cut(text.trim(), minOf(MAX_PAGE, budget))
                    budget -= excerpt.length
                    pages += SourcePage(url, excerpt)
                    progress(ResearchStage.Reading, pages.size)
                }
            }
        }
        return pages
    }

    private suspend fun fetch(url: String, native: EngineFeatures): String? = try {
        withTimeoutOrNull(FETCH_TIMEOUT) { search.value.fetch(url, native) }?.text
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "research page unreadable" }
        null
    }

    private suspend fun write(job: ResearchJob, known: ModelFacts?, pages: List<SourcePage>): ResearchOutcome {
        val prompt = synthesisPrompt(job.subject, known, pages, Fence.random())
        var reason: String? = null
        var outcome: ResearchOutcome? = null
        var attempt = 0
        while (outcome == null && attempt < ATTEMPTS) {
            attempt++
            val text = reason?.let { repairPrompt(prompt, it) } ?: prompt
            outcome = when (val answer = prompts.value.ask(IsolatedPrompt(job.researcher, text, TIMEOUT, MAX_ANSWER))) {
                is IsolatedAnswer.Unavailable -> {
                    log.w { "research ${job.id}: researcher unavailable (${answer.reason})" }
                    ResearchOutcome.Failed(ResearchFailure.ResearcherUnavailable)
                }

                is IsolatedAnswer.Answered -> when (val parsed = parse(job, answer.text, pages, known)) {
                    is ParsedDescription.Valid -> ResearchOutcome.Written(parsed.profile)

                    is ParsedDescription.Invalid -> {
                        log.w { "research ${job.id}: invalid description, attempt $attempt" }
                        reason = parsed.reason
                        null
                    }
                }
            }
        }
        return outcome ?: ResearchOutcome.Failed(ResearchFailure.InvalidAnswer)
    }

    private fun parse(job: ResearchJob, answer: String, pages: List<SourcePage>, known: ModelFacts?) =
        parseDescription(answer, job.subject, pages, known, clock.now())

    private companion object {
        const val RESULTS = 5
        const val MAX_PAGE = 6_000
        const val MAX_TOTAL = 30_000
        const val ATTEMPTS = 2
        const val MAX_ANSWER = 16 * 1024
        val SEARCH_TIMEOUT = 30.seconds
        val FETCH_TIMEOUT = 20.seconds
        val TIMEOUT = 5.minutes
    }
}
