package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionObservationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
internal class StudioSessionViewer(
    private val facade: EngineFacade,
    private val saved: Lazy<StudioTranscripts>,
    private val contextAllowed: (ExecutionRoute) -> Flow<Boolean> = { flowOf(false) },
) : StudioSessionViews {
    constructor(
        facade: EngineFacade,
        @ForScope(ProfileScope::class) stores: DataStores,
        usage: EngineStudioUsage,
    ) : this(
        facade,
        lazy {
            StudioTranscripts(
                stores.database(StudioTranscriptDatabaseSpec).transcripts(),
                legacy = { emptyList() },
                contracted = {},
            )
        },
        usage::allowed,
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
        launch {
            while (true) {
                record(ref, reopening, isLive = true)
                delay(NATIVE_HISTORY_RETRY_MILLIS)
            }
        }
        combine(saved.value.observe(ref.transcriptKey()), observation(ref)) { items, snapshot ->
            items.toStudioMessages(Instant.DISTANT_PAST, isRunning = snapshot?.state?.activeTurn() != null)
        }.collect { send(it) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observation(ref: SessionRef): Flow<SessionObservationSnapshot?> = flow {
        try {
            val access = facade.sessions.get(ref).features.resolve(SessionObservation)
            val snapshots = (access as? FeatureAccess.Available)?.feature?.snapshots ?: flowOf(null)
            emitAll(
                snapshots.flatMapLatest { snapshot ->
                    if (snapshot == null) {
                        flowOf(null)
                    } else {
                        contextAllowed(snapshot.route).map { allowed ->
                            snapshot.copy(context = snapshot.context.takeIf { allowed })
                        }
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Viewed session observation failed" }
            emit(null)
        }
    }

    override fun tree(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot> = flow {
        when (val feature = facade.engines.features(root.engine).resolve(SessionTrees)) {
            is FeatureAccess.Available -> emitAll(feature.feature.observe(root, access))
            is FeatureAccess.Unavailable -> emit(SessionTreeSnapshot(root, coverage = SessionTreeCoverage.Unavailable))
            FeatureAccess.Unsupported -> emit(SessionTreeSnapshot(root, coverage = SessionTreeCoverage.Unsupported))
        }
    }

    override fun observeNative(
        root: SessionRef,
        ref: SessionRef,
        access: SessionTreeAccess,
    ): Flow<List<StudioMessage>> = channelFlow {
        launch {
            while (true) {
                try {
                    val feature = facade.engines.features(root.engine).requireFeature(SessionTrees)
                    val history = feature.history(root, ref, access)
                    val reader = readersLock.withLock { readers.getOrPut(ref) { Mutex() } }
                    reader.withLock { mirror.follow(ref.transcriptKey(), history) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "Native child history stopped updating; retrying" }
                }
                delay(NATIVE_HISTORY_RETRY_MILLIS)
            }
        }
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
        val trees = facade.engines.features(ref.engine).resolve(SessionTrees)
        if (trees is FeatureAccess.Available) {
            return block(trees.feature.history(ref, ref, SessionTreeAccess(reopening.target, reopening.workspace)))
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

private const val NATIVE_HISTORY_RETRY_MILLIS = 2_000L
