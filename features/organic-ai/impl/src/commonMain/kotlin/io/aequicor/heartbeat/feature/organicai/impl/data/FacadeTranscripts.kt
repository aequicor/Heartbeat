package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.organicai.impl.domain.SessionTranscripts

/** The newest history window of a stored cell session; reading never resumes the session. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class FacadeTranscripts(private val facade: EngineFacade) : SessionTranscripts {
    private val log = Log.tag("FacadeTranscripts")

    override suspend fun recent(session: SessionRef): List<SessionItem> {
        val history = facade.sessions.get(session).features.resolve(SessionHistory).orThrow()
        val items = history.page(HistoryPageRequest(limit = ITEMS)).items
        log.v { "read ${items.size} recent items from ${session.engine.value}" }
        return items
    }

    private companion object {
        const val ITEMS = 80
    }
}
