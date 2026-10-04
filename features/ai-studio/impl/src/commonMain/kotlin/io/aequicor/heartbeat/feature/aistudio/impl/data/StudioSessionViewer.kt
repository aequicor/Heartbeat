package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * Read-only live view of a native session the studio does not drive, such as a cell or a judge of an organism. The
 * history is mirrored in memory only while observed; nothing is stored and the session is never resumed.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class StudioSessionViewer(private val facade: EngineFacade) : StudioSessionViews {
    private val log = Log.tag("StudioSessionViewer")

    override fun observe(ref: SessionRef): Flow<List<StudioMessage>> = channelFlow {
        val items = MutableStateFlow<List<SessionItem>>(emptyList())
        val mirror = StudioHistoryMirror(read = { items.value }, update = { _, change -> items.update(change) })
        launch {
            try {
                val history = facade.sessions.get(ref).features.requireFeature(SessionHistory)
                log.d { "follow a ${ref.engine.value} session for viewing" }
                mirror.follow(ref.nativeId, history)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The view keeps what it read; an ended or unreadable session simply stops updating.
                log.w(e) { "viewed ${ref.engine.value} session stopped updating" }
            }
        }
        items.collect { send(it.toStudioMessages(Instant.DISTANT_PAST, isRunning = false)) }
    }
}
