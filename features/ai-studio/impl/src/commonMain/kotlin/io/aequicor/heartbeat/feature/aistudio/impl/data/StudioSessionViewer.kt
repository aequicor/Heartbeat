package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Durable read-only view of a session the studio does not drive. Profile recording and screen observers share
 * one native reader per full session identity; a second reader waits while the first keeps the transcript current.
 * Partial native replay retains the saved prefix, including when a resumed engine returns no canonical items.
 * No prompt is sent and closing a reader never cancels a native turn.
 */
internal class StudioSessionViewer(private val facade: EngineFacade, private val saved: Lazy<StudioTranscripts>) :
    StudioSessionViews {
    constructor(
        facade: EngineFacade,
        @ForScope(ProfileScope::class) stores: DataStores,
    ) : this(
        facade,
        lazy {
            StudioTranscripts(
                stores.database(StudioTranscriptDatabaseSpec).transcripts(),
                legacy = { emptyList() },
                contracted = {},
            )
        },
    )

    private val log = Log.tag("StudioSessionViewer")
    private val storage = Mutex()
    private val readers = mutableMapOf<SessionRef, Mutex>()
    private val readersLock = Mutex()
    private val mirror = StudioHistoryMirror(
        read = { id -> storage.withLock { saved.value.read(id) } },
        update = { id, change ->
            storage.withLock {
                val previous = saved.value.read(id)
                saved.value.replace(id, change(previous), previous)
            }
        },
    )

    override fun observe(ref: SessionRef, reopening: ResumeSessionRequest): Flow<List<StudioMessage>> = channelFlow {
        launch { record(ref, reopening, isLive = true) }
        saved.value.observe(ref.transcriptKey()).collect {
            send(it.toStudioMessages(Instant.DISTANT_PAST, isRunning = false))
        }
    }

    /** Records a complete snapshot or follows a living session; cancellation flushes its final native snapshot. */
    suspend fun record(ref: SessionRef, reopening: ResumeSessionRequest, isLive: Boolean) {
        val reader = readersLock.withLock { readers.getOrPut(ref) { Mutex() } }
        reader.withLock {
            try {
                follow(ref, reopening) { history ->
                    val key = ref.transcriptKey()
                    if (isLive) {
                        try {
                            mirror.follow(key, history)
                        } finally {
                            withContext(NonCancellable) { refreshFinally(key, history) }
                        }
                    } else {
                        mirror.refresh(key, history)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "viewed ${ref.engine.value} session stopped updating" }
            }
        }
    }

    private suspend fun refreshFinally(key: String, history: SessionHistory) {
        try {
            val refreshed = withTimeoutOrNull(5_000) { mirror.refresh(key, history) }
            if (refreshed == null) log.w { "Timed out saving the viewed session's final snapshot" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "viewed session's final snapshot could not be saved" }
        }
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

/** Namespace separates native views from ordinary studio chat ids and includes engine and source identity. */
private fun SessionRef.transcriptKey(): String = "native:" + Json.encodeToString(SessionRef.serializer(), this)
