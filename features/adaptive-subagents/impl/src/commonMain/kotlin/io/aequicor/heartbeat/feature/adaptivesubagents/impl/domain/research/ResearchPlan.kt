package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research

import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchFailure
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchJob
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchStage
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchSubject
import io.aequicor.heartbeat.feature.aiengine.facade.api.brief

/** What a research run produced. */
internal sealed interface ResearchOutcome {
    data class Written(val profile: ModelProfile) : ResearchOutcome

    data class Failed(val failure: ResearchFailure) : ResearchOutcome
}

/** Writes a description of a model; [progress] reports stages and pages read. */
internal fun interface ModelResearch {
    suspend fun research(job: ResearchJob, progress: suspend (ResearchStage, Int) -> Unit): ResearchOutcome
}

/** Web queries about [subject]: its card, benchmarks, strengths and, with efforts, how effort changes it. */
internal fun researchQueries(subject: ResearchSubject): List<String> {
    val title = brief(subject.title)
    return listOfNotNull(
        "$title model card",
        "${subject.key.model.value} benchmarks",
        "$title strengths weaknesses coding",
        "$title reasoning effort low high".takeIf { subject.efforts.isNotEmpty() },
    )
}

/**
 * Pages worth reading among found [urls]: distinct addresses without fragments, vendor and catalog pages first,
 * at most [limit].
 */
internal fun pickPages(urls: List<String>, limit: Int = MAX_PAGES): List<String> = urls.asSequence()
    .map { it.substringBefore('#') }
    .filter { it.startsWith("https://") || it.startsWith("http://") }
    .distinct()
    .sortedBy { url -> if (PREFERRED.any { it in hostOf(url) }) 0 else 1 }
    .take(limit)
    .toList()

private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').lowercase()

internal const val MAX_PAGES = 6
private val PREFERRED = listOf(
    "openai.com", "anthropic.com", "ai.google.dev", "deepmind.google", "mistral.ai", "qwen", "deepseek",
    "models.dev", "huggingface.co", "artificialanalysis.ai", "docs.",
)
