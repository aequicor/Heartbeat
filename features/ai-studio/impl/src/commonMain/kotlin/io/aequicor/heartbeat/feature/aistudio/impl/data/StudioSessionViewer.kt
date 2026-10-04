package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * Read-only live view of a native session the studio does not drive, such as a cell or a judge of an organism. The
 * history is mirrored in memory only while observed and nothing is stored. The stored session's history is read
 * when its engine keeps one; an engine that serves history only to open sessions (Codex) gets the session reopened
 * with its owner's request, and that extra handle is closed when the view ends. No prompt is ever sent.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class StudioSessionViewer(private val facade: EngineFacade) : StudioSessionViews {
    private val log = Log.tag("StudioSessionViewer")

    override fun observe(ref: SessionRef, reopening: ResumeSessionRequest): Flow<List<StudioMessage>> = channelFlow {
        val items = MutableStateFlow<List<SessionItem>>(emptyList())
        val mirror = StudioHistoryMirror(read = { items.value }, update = { _, change -> items.update(change) })
        launch {
            try {
                follow(ref, reopening) { history -> mirror.follow(ref.nativeId, history) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The view keeps what it read; an ended or unreadable session simply stops updating.
                log.w(e) { "viewed ${ref.engine.value} session stopped updating" }
            }
        }
        items.collect { send(it.toStudioMessages(Instant.DISTANT_PAST, isRunning = false)) }
    }

    private suspend fun follow(
        ref: SessionRef,
        reopening: ResumeSessionRequest,
        block: suspend (SessionHistory) -> Unit,
    ) {
        val stored = facade.sessions.get(ref).features
        val history = stored.resolve(SessionHistory)
        if (history is FeatureAccess.Available) {
            log.d { "follow a stored ${ref.engine.value} session for viewing" }
            return block(history.feature)
        }
        val opened = stored.requireFeature(ResumesSessions).resume(reopening)
        log.d { "follow a reopened ${ref.engine.value} session for viewing" }
        try {
            block(opened.features.requireFeature(SessionHistory))
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
            log.w(e) { "reopened ${opened.ref.engine.value} session was not closed after viewing" }
        }
    }
}
