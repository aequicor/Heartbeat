package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchiveFilter
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.DiscoveryStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrder
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SourceDiscovery
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineAdapter
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogSessionRecords
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class DefaultKoogEngineAdapter(
    private val access: KoogAccess,
    private val records: KoogSessionRecords,
    private val cache: KoogSessionCache,
    private val search: SearchEngine,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : KoogEngineAdapter {
    private val log = Log.tag("KoogEngine")
    private val mutex = Mutex()
    private val runtimes = mutableMapOf<AuthSourceId, KoogRuntime>()
    private val pages = linkedMapOf<String, Pair<SessionQuery, List<SessionSummary>>>()
    override val source: SessionSource = KoogSessionSource
    override val discovery: ListsSessions = this

    init {
        profile.onClose {
            // Main dispatcher only, like every mutation of this adapter; the copy tolerates re-entrant changes.
            runtimes.values.toList().forEach { it.dispose() }
        }
    }

    override suspend fun checkRequirements(): EngineAvailability = EngineAvailability.Available

    override fun accepts(source: AuthSource, context: EngineContext): Boolean =
        context.engine == KoogEngineId && koogProvider(source) != null

    override fun authContext(context: EngineContext): AuthContextKey = AuthContextKey("koog.provider")

    /** Stores the provider route of [binding]; credentials stay in the profile vault. */
    override suspend fun bind(binding: EngineBindingId, source: AuthSource) {
        if (access.configure(binding, source)) log.i { "Bound Koog route" }
    }

    /** Forgets the provider route of [binding]; unknown bindings are ignored. */
    override suspend fun unbind(binding: EngineBindingId) {
        if (access.remove(binding)) log.i { "Unbound Koog route" }
    }

    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> = onMain {
        withProfile {
            val connection = access.route(context.binding)
            if (!accepts(source, context) || source != connection.source) {
                fail(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
            }
            log.i { "Discovering provider models" }
            val provider = requireNotNull(koogProvider(connection.source))
            koogCall {
                access.open(connection).use { client ->
                    val models = client.models().distinctBy { it.id }
                    val ids = models.map { it.id }
                    val levels = access.reasoning.discover(provider, ids, client.reasoning(ids))
                    models.map {
                        ModelInfo(
                            EngineTarget(KoogEngineId, context.binding, ModelId(it.id)),
                            title = it.id,
                            features = setOf(SendsPrompts.id, CancelsTurns.id, SessionHistory.id),
                            contextLimitTokens = it.contextLength?.takeIf { limit -> limit > 0 },
                            reasoningEfforts = levels[it.id].orEmpty(),
                        )
                    }
                }
            }
        }
    }

    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = onMain {
        mutex.withLock {
            access.source(identity)
            val current = runtimes[identity.source]
            if (current != null && !current.isClosed && current.identity == identity) return@withLock current
            current?.close()
            access.checkEnabled()
            log.i { "Creating profile runtime" }
            KoogRuntime(identity, access, records, profile.coroutineScope, cache, search).also {
                runtimes[identity.source] = it
            }
        }
    }

    override suspend fun get(ref: SessionRef): EngineSession = onMain {
        access.checkEnabled()
        val record = koogCall { records.get(ref) } ?: fail(EngineFailure.Session(SessionFailureReason.NotFound))
        access.checkEnabled()
        val snapshot = cache.get(record)
        object : EngineSession {
            override val summary = snapshot.summary
            override val features = KoogFeatures(
                SessionHistory to object : SessionHistory {
                    override suspend fun page(request: HistoryPageRequest): HistoryPage =
                        onMain { history(ref).page(request) }

                    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> =
                        flow { emitAll(history(ref).watch(after)) }
                            .flowOn(profile.coroutineScope.coroutineContext.minusKey(Job))
                },
                ResumesSessions to object : ResumesSessions {
                    override suspend fun resume(request: ResumeSessionRequest): ActiveSession {
                        val connection = access.route(request.target.binding)
                        val identity = RuntimeIdentity(
                            KoogEngineId,
                            connection.source.info.id,
                            connection.source.info.revision,
                        )
                        val runtime = createRuntime(identity)
                        val attach = runtime.features.resolve(AttachesSessions) as? FeatureAccess.Available
                            ?: fail(EngineFailure.Session(SessionFailureReason.NotResumable))
                        return attach.feature.attach(ref, request)
                    }
                },
            )
        }
    }

    /** Transcript of a stored session; reloaded from storage when the cache evicted it. */
    private suspend fun history(ref: SessionRef): KoogHistory = cache.history(ref) {
        access.checkEnabled()
        koogCall { records.get(ref) } ?: fail(EngineFailure.Session(SessionFailureReason.NotFound))
    }

    override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage = onMain {
        access.checkEnabled()
        log.d { "Listing stored sessions" }
        val parts = request.cursor?.value?.split(':')
        val token = parts?.first() ?: Uuid.random().toString().also { key ->
            val entries = koogCall { records.list() }.map { it.summary }.filter { it.matches(query) }
            val sorted = when (query.order) {
                SessionOrder.RecentlyCreated -> entries.sortedWith(
                    compareByDescending<SessionSummary> { it.times.createdAt }.thenBy { it.ref.nativeId },
                )

                SessionOrder.RecentlyUpdated -> entries.sortedWith(
                    compareByDescending<SessionSummary> { it.times.updatedAt }.thenBy { it.ref.nativeId },
                )
            }
            pages[key] = query to sorted
            if (pages.size > PAGE_SNAPSHOTS) pages.remove(pages.keys.first())
        }
        access.checkEnabled()
        val snapshot = pages[token] ?: fail(EngineFailure.History(HistoryFailureReason.CursorExpired))
        val offset = parts?.getOrNull(1)?.toIntOrNull() ?: if (parts == null) 0 else -1
        if (snapshot.first != query || offset !in 0..snapshot.second.size) {
            fail(EngineFailure.History(HistoryFailureReason.CursorExpired))
        }
        val end = (offset + request.limit).coerceAtMost(snapshot.second.size)
        SessionPage(
            snapshot.second.subList(offset, end),
            if (end < snapshot.second.size) SessionCursor("$token:$end") else null,
            listOf(SourceDiscovery(source, DiscoveryStatus.Complete, Observation(isStale = false))),
        )
    }

    private suspend fun <T> withProfile(block: suspend () -> T): T = coroutineScope {
        val operation = currentCoroutineContext().job
        val registration = profile.onClose { operation.cancel() }
        try {
            access.checkEnabled()
            block()
        } finally {
            registration.dispose()
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T =
        withContext(profile.coroutineScope.coroutineContext.minusKey(Job)) { block() }

    private companion object {
        const val PAGE_SNAPSHOTS = 32
    }
}

private fun SessionSummary.matches(query: SessionQuery): Boolean =
    (query.engines.isEmpty() || ref.engine in query.engines) &&
        (query.sources.isEmpty() || ref.source in query.sources) &&
        (query.workspace == null || workspace == query.workspace) &&
        (query.search == null || title.orEmpty().contains(query.search.orEmpty(), ignoreCase = true)) &&
        when (query.archive) {
            ArchiveFilter.All -> true
            ArchiveFilter.Active -> !isArchived
            ArchiveFilter.Archived -> isArchived
        }
