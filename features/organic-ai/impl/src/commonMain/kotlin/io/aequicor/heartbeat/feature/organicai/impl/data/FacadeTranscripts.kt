package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.organicai.api.OrganismSession
import io.aequicor.heartbeat.feature.organicai.impl.domain.SessionTranscripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The newest history window of a cell session. The stored session's history is read when the engine keeps one;
 * an engine that serves history only to open sessions (Codex) gets the session reopened as the organism opened it,
 * so the read never changes the cell's tools, and the extra handle is closed right after.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class FacadeTranscripts(private val facade: EngineFacade) : SessionTranscripts {
    private val log = Log.tag("FacadeTranscripts")

    override suspend fun recent(session: OrganismSession): List<SessionItem> {
        val stored = facade.sessions.get(session.ref).features
        val history = stored.resolve(SessionHistory)
        if (history is FeatureAccess.Available) return history.feature.page(HistoryPageRequest(limit = ITEMS)).items
        val opened = stored.resolve(ResumesSessions).orThrow().resume(session.reopening)
        try {
            val items = opened.features.resolve(SessionHistory).orThrow().page(HistoryPageRequest(limit = ITEMS)).items
            log.v { "read ${items.size} recent items from a reopened ${session.ref.engine.value} session" }
            return items
        } finally {
            withContext(NonCancellable) { closeQuietly(opened) }
        }
    }

    private suspend fun closeQuietly(opened: ActiveSession) {
        try {
            opened.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "reopened ${opened.ref.engine.value} session was not closed" }
        }
    }

    private companion object {
        const val ITEMS = 80
    }
}
