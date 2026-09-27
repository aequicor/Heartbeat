package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.SessionTranscripts
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.Transcript
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.approximateLength

/**
 * Reads the newest history windows of a stored session through the facade, walking older cursors until the
 * text budget or [MAX_PAGES] is reached. Never resumes the session or generates text.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class FacadeSessionTranscripts(
    // Optional until an application bundle installs the AI engine facade.
    private val facade: EngineFacade = MissingEngineFacade,
) : SessionTranscripts {

    private val log = Log.tag("FacadeSessionTranscripts")

    override suspend fun read(source: SessionRef, budgetChars: Int): Transcript {
        val history = facade.sessions.get(source).features.resolve(SessionHistory).orThrow()
        val items = ArrayDeque<SessionItem>()
        var coverage = HistoryCoverage.Complete
        var request: HistoryPageRequest? = HistoryPageRequest()
        var collected = 0
        var pages = 0
        while (request != null && collected < budgetChars && pages < MAX_PAGES) {
            val page = history.page(request)
            pages++
            items.addAll(0, page.items)
            collected += page.items.sumOf { it.approximateLength() }
            if (page.coverage != HistoryCoverage.Complete) coverage = page.coverage
            request = page.older?.let { HistoryPageRequest(it) }
        }
        log.d { "history read from ${source.engine.value}: $pages pages, ${items.size} items, coverage=$coverage" }
        return Transcript(items.toList(), coverage, isTruncated = request != null)
    }

    private companion object {
        const val MAX_PAGES = 20
    }
}
