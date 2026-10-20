package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchivesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionDiscoveryReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.assertEquals

internal class IsolatedTurnFixture {
    val target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("model"))
    val ref = SessionRef(target.engine, SessionSourceId("source"), "review")
    val policy = AgentSafetyToolPolicy()
    var sawOffBeforeSend = false
    var closed = false
    var archived = false
    var cancels = 0
    var reconciles = 0
    var outcome: TurnOutcome? = TurnOutcome.Completed
    var ignoresCancel = false
    var hangsOnClose = false
    var asks = false
    var failsOnCreate = false
    var failsOnSend = false
    var failsOnReconcile = false
    var remainsActiveAfterReconcile = false
    var createGate: CompletableDeferred<Unit>? = null
    var finishesUnavailable = false
    var answer = """{"verdict":"safe","reason":"Only formats JSON"}"""
    val creations = mutableListOf<CreateSessionRequest>()
    val prompts = mutableListOf<PromptRequest>()
    private val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    private val turn = TurnId("review-turn")
    private val sends = object : SendsPrompts {
        override suspend fun send(request: PromptRequest): TurnId {
            prompts += request
            sawOffBeforeSend = policy.policy(ToolPolicyScope(session = ref))?.native?.get("shell") == ToolSwitch.Off
            assertEquals(TrustLevel.Ask, request.trust)
            val current = Turn(turn, request.id, target)
            if (failsOnSend) {
                state.value = ActiveSessionState.Unavailable(failure, activeTurn = current)
                error("private source and provider credentials")
            }
            state.value = when {
                asks -> ActiveSessionState.AwaitingUserAction(
                    current,
                    listOf(
                        PermissionRequest(
                            PermissionRequestId("p"),
                            turn,
                            "Unexpected tool",
                            listOf(PermissionOption(PermissionOptionId("yes"), "Yes")),
                        ),
                    ),
                )

                outcome != null && finishesUnavailable ->
                    ActiveSessionState.Unavailable(failure, lastTurn = current.copy(outcome = outcome))

                outcome != null -> ActiveSessionState.Ready(current.copy(outcome = outcome))

                else -> ActiveSessionState.Running(current)
            }
            return turn
        }
    }
    private val cancel = object : CancelsTurns {
        override suspend fun cancel(turn: TurnId) {
            cancels++
            check(state.value is ActiveSessionState.Running || state.value is ActiveSessionState.AwaitingUserAction)
            if (!ignoresCancel) state.value = ActiveSessionState.Ready(Turn(turn, null, target, TurnOutcome.Cancelled))
        }
    }
    private val reconciler = object : ReconcilesSession {
        override suspend fun synchronize() {
            reconciles++
            check(!failsOnReconcile) { "private source and provider credentials" }
            val current = checkNotNull((state.value as ActiveSessionState.Unavailable).activeTurn)
            state.value = if (remainsActiveAfterReconcile) {
                ActiveSessionState.Running(current)
            } else {
                ActiveSessionState.Ready(current.copy(outcome = TurnOutcome.Unknown))
            }
        }
    }
    private val history = object : SessionHistory {
        override suspend fun page(request: HistoryPageRequest) = HistoryPage(
            listOf(
                SessionItem.Message(
                    ItemInfo(ItemId("answer"), 0, 0, turn),
                    MessageRole.Assistant,
                    listOf(ContentPart.Text(answer)),
                ),
            ),
            null,
            null,
            HistoryCheckpoint("checkpoint"),
            HistoryCoverage.Complete,
        )
        override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = emptyFlow()
    }
    private val session = object : ActiveSession {
        override val ref = this@IsolatedTurnFixture.ref
        override val route: ExecutionRoute get() = error("unused")
        override val state = this@IsolatedTurnFixture.state
        override val features = reviewFeatures(
            mapOf(
                SendsPrompts to sends,
                CancelsTurns to cancel,
                ReconcilesSession to reconciler,
                SessionHistory to history,
            ),
        )
        override suspend fun close() {
            if (hangsOnClose) awaitCancellation()
            closed = true
            state.value = ActiveSessionState.Closed
        }
    }
    private val creates = object : CreatesSessions {
        override suspend fun create(request: CreateSessionRequest): ActiveSession {
            createGate?.await()
            check(!failsOnCreate) { "private source and provider credentials" }
            creations += request
            state.value = ActiveSessionState.Ready()
            closed = false
            return session
        }
    }
    val facade = object : EngineFacade {
        override val engines = object : EngineCatalog {
            override val state = MutableStateFlow(
                listOf(
                    EngineInfo(
                        EngineDescriptor(
                            target.engine,
                            "Test",
                            EngineFamily.BuiltIn,
                            emptySet(),
                            FeatureToggle.Flag("ai.safety.test", "Safety reviewer test"),
                            nativeTools = listOf(NativeToolSpec("shell", AgentToolAction.Command, true, false)),
                        ),
                        EngineAvailability.Available,
                        emptyList(),
                    ),
                ),
            )
            override suspend fun refresh(engine: EngineId): EngineInfo = error("unused")
            override fun features(engine: EngineId) = reviewFeatures(mapOf(CreatesSessions to creates))
        }
        override val sessions = object : SessionCatalog {
            override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage = error("unused")
            override suspend fun refresh(query: SessionQuery): SessionDiscoveryReport = error("unused")
            override suspend fun get(ref: SessionRef) = object : EngineSession {
                override val summary = MutableStateFlow(SessionSummary(ref))
                override val features = reviewFeatures(
                    mapOf(
                        ArchivesSessions to object : ArchivesSessions {
                            override suspend fun setArchived(archived: Boolean) {
                                this@IsolatedTurnFixture.archived = archived
                            }
                        },
                    ),
                )
            }
        }
        override val bindings: EngineBindings get() = error("unused")
        override val models: ModelCatalog get() = error("unused")
        override val providerUsage: ProviderUsageCatalog get() = error("unused")
    }

    fun finish(turnId: TurnId = turn, isUnavailable: Boolean = false) {
        val completed = Turn(turnId, null, target, TurnOutcome.Cancelled)
        state.value = if (isUnavailable) {
            ActiveSessionState.Unavailable(failure, lastTurn = completed)
        } else {
            ActiveSessionState.Ready(completed)
        }
    }

    fun detach() {
        state.value = ActiveSessionState.Closed
    }

    private val failure = EngineFailure.Request(RequestFailureReason.OutcomeUnknown)
}

internal fun reviewFeatures(entries: Map<EngineFeatureKey<*>, EngineFeature>) = object : EngineFeatures {
    @Suppress("UNCHECKED_CAST") // Test registrations pair each feature with its own key.
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
        (entries[key] as F?)?.let { FeatureAccess.Available(it) } ?: FeatureAccess.Unsupported
}
