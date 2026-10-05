package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchivesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.BindingCheck
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionDiscoveryReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A native session whose engine answers every prompt at once with [finish] (null keeps the turn running) and
 * whose history is [items]. A [sendFailure] fails submissions the way the facade does, leaving the session
 * unavailable with the submitted turn; synchronization moves it to [synchronized] (null fails it). It records
 * prompts, cancellations, decisions, synchronizations, archiving and closes.
 */
internal class FakeSession(override val ref: SessionRef, private val hasTrust: Boolean = true) : ActiveSession {
    var historyFailure: EngineFailure? = null
    var sendFailure: EngineFailure? = null
    var synchronized: ActiveSessionState? = null
    var synchronizations = 0

    /** The state a resumption finds the session in. */
    var resumedAs: ActiveSessionState = ActiveSessionState.Ready()

    /** Thrown by cancellation; with [ignoresCancel] a cancelled turn runs on. */
    var cancelFailure: Throwable? = null
    var ignoresCancel = false
    val prompts = mutableListOf<PromptRequest>()
    val cancelled = mutableListOf<TurnId>()
    val decisions = mutableListOf<PermissionDecision>()
    var items: List<SessionItem> = emptyList()
    var finish: TurnOutcome? = TurnOutcome.Completed
    var isArchived = false
    var closes = 0

    override val route: ExecutionRoute get() = error("unused")
    override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())

    override suspend fun close() {
        closes++
        state.value = ActiveSessionState.Closed
    }

    /** Ends the running turn with [outcome]. */
    fun end(turn: TurnId, outcome: TurnOutcome) {
        state.value = ActiveSessionState.Ready(Turn(turn, null, TARGET, outcome))
    }

    private val sends = object : SendsPrompts {
        override suspend fun send(request: PromptRequest): TurnId {
            prompts += request
            val turn = TurnId("turn-${prompts.size}")
            sendFailure?.let { failure ->
                state.value = ActiveSessionState.Unavailable(failure, Turn(turn, request.id, TARGET))
                throw EngineException(failure)
            }
            state.value = ActiveSessionState.Running(Turn(turn, request.id, TARGET))
            finish?.let { end(turn, it) }
            return turn
        }
    }

    private val cancels = object : CancelsTurns {
        override suspend fun cancel(turn: TurnId) {
            cancelled += turn
            cancelFailure?.let { throw it }
            if (!ignoresCancel) end(turn, TurnOutcome.Cancelled)
        }
    }

    private val reconciles = object : ReconcilesSession {
        override suspend fun synchronize() {
            synchronizations++
            state.value = synchronized ?: throw EngineException(EngineFailure.Unknown())
        }
    }

    private val permissions = object : RequestsPermissions {
        override suspend fun respond(decision: PermissionDecision) {
            decisions += decision
        }
    }

    val history = object : SessionHistory {
        override suspend fun page(request: HistoryPageRequest): HistoryPage {
            historyFailure?.let { throw EngineException(it) }
            return HistoryPage(items, null, null, HistoryCheckpoint("checkpoint"), HistoryCoverage.Complete)
        }

        override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = emptyFlow()
    }

    val archives = object : ArchivesSessions {
        override suspend fun setArchived(archived: Boolean) {
            isArchived = archived
        }
    }

    override val features: EngineFeatures = FakeFeatures(
        buildMap {
            put(SendsPrompts, FeatureAccess.Available(sends))
            put(CancelsTurns, FeatureAccess.Available(cancels))
            put(RequestsPermissions, FeatureAccess.Available(permissions))
            put(SessionHistory, FeatureAccess.Available(history))
            put(ReconcilesSession, FeatureAccess.Available(reconciles))
            if (hasTrust) put(AppliesTrustLevels, FeatureAccess.Available(object : AppliesTrustLevels {}))
        },
    )
}

/** Capability set resolving exactly the registered features; everything else is Unsupported. */
internal class FakeFeatures(private val features: Map<EngineFeatureKey<*>, FeatureAccess<*>>) : EngineFeatures {
    @Suppress("UNCHECKED_CAST") // Test fake: keys and accesses are registered in matching pairs.
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
        features[key] as FeatureAccess<F>? ?: FeatureAccess.Unsupported
}

/**
 * A facade that creates [created] sessions in order and resumes or reads the [stored] ones. Without [hasStoredHistory]
 * stored sessions serve history only once reopened, as Codex sessions do.
 */
internal class FakeFacade(
    private val created: ArrayDeque<FakeSession> = ArrayDeque(),
    private val stored: Map<SessionRef, FakeSession> = emptyMap(),
    private val hasStoredHistory: Boolean = true,
) : EngineFacade {
    val creations = mutableListOf<CreateSessionRequest>()
    var onCreate: suspend () -> Unit = {}
    var onResume: suspend () -> Unit = {}
    val resumptions = mutableListOf<ResumeSessionRequest>()

    private val creates = object : CreatesSessions {
        override suspend fun create(request: CreateSessionRequest): ActiveSession {
            creations += request
            onCreate()
            return created.removeFirst()
        }
    }

    override val engines: EngineCatalog = object : EngineCatalog {
        override val state: StateFlow<List<EngineInfo>> = MutableStateFlow(emptyList())
        override suspend fun refresh(engine: EngineId): EngineInfo = error("unused")
        override fun features(engine: EngineId): EngineFeatures =
            FakeFeatures(mapOf(CreatesSessions to FeatureAccess.Available(creates)))
    }

    override val sessions: SessionCatalog = object : SessionCatalog {
        override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage = error("unused")
        override suspend fun refresh(query: SessionQuery): SessionDiscoveryReport = error("unused")
        override suspend fun get(ref: SessionRef): EngineSession {
            val session = stored[ref] ?: allCreated.first { it.ref == ref }
            val resumes = object : ResumesSessions {
                override suspend fun resume(request: ResumeSessionRequest): ActiveSession {
                    resumptions += request
                    session.state.value = session.resumedAs
                    onResume()
                    return session
                }
            }
            return object : EngineSession {
                override val summary: StateFlow<SessionSummary> = MutableStateFlow(SessionSummary(ref))
                override val features: EngineFeatures = FakeFeatures(
                    buildMap {
                        put(ResumesSessions, FeatureAccess.Available(resumes))
                        if (hasStoredHistory) put(SessionHistory, FeatureAccess.Available(session.history))
                        put(ArchivesSessions, FeatureAccess.Available(session.archives))
                    },
                )
            }
        }
    }

    private val allCreated = created.toList()

    override val providerUsage = object : ProviderUsageCatalog {
        private val snapshot = MutableStateFlow(ProviderUsageSnapshot())
        override fun observe(engine: EngineId, binding: EngineBindingId) = snapshot
        override suspend fun refresh(engine: EngineId, binding: EngineBindingId) = snapshot.value
    }

    override val bindings: EngineBindings = object : EngineBindings {
        override val state: StateFlow<List<EngineBinding>> = MutableStateFlow(emptyList())
        override suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int) = error("unused")
        override suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean) = error("unused")
        override suspend fun disconnect(binding: EngineBindingId) = error("unused")
        override suspend fun check(target: EngineTarget, workspace: WorkspaceRef?): BindingCheck = error("unused")
    }

    override val models: ModelCatalog get() = error("unused")
}
