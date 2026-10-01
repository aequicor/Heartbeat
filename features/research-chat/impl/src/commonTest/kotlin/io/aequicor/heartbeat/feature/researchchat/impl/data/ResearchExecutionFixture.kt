package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachments
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchStorage
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class ResearchExecutionFixture(
    scope: CoroutineScope,
    attachments: ResearchAttachments = ResearchAttachments.Legacy,
) {
    val storage = ExecutionStorage()
    val native = mutableListOf<ExecutionSession>()
    private val creator = object : CreatesSessions {
        override suspend fun create(request: CreateSessionRequest): ActiveSession {
            assertNull(request.workspace)
            return ExecutionSession(request.target, "native-${native.size}") { ref ->
                val question = storage.read().flatMap { it.questions }.first { it.ref == ref }
                assertNotNull(question.pendingSegmentStart, "Native acceptance must have a durable recovery marker")
            }.also { native += it }
        }
    }
    private val facade = object : EngineFacade {
        override val providerUsage = object : ProviderUsageCatalog {
            private val snapshot = MutableStateFlow(ProviderUsageSnapshot())
            override fun observe(engine: EngineId, binding: EngineBindingId) = snapshot
            override suspend fun refresh(engine: EngineId, binding: EngineBindingId) = snapshot.value
        }

        override val engines = object : EngineCatalog {
            override val state = MutableStateFlow<List<EngineInfo>>(emptyList())
            override suspend fun refresh(engine: EngineId): EngineInfo = error("Unexpected refresh")
            override fun features(engine: EngineId): EngineFeatures = ExecutionFeatures(CreatesSessions to creator)
        }
        override val bindings: EngineBindings get() = error("Unexpected binding access")
        override val models: ModelCatalog = object : ModelCatalog {
            private val snapshot = MutableStateFlow(ModelCatalogSnapshot(emptyList(), Observation()))
            override fun observe(engine: EngineId, binding: EngineBindingId) = snapshot
            override suspend fun refresh(engine: EngineId, binding: EngineBindingId) = error("Unexpected refresh")
        }
        override val sessions: SessionCatalog get() = error("Unexpected recovery")
    }
    private val toggles = object : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // The execution fixture only receives boolean research and Koog flags.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = true as T
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flow { emit(get(toggle)) }
    }
    private val search = object : SearchEngine {
        override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> =
            error("Unexpected search")

        override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent =
            error("Web-fetch tool already supplied complete source content")
    }
    private val profile = object : ScopeHandle {
        override val name = "research-test-profile"
        override val coroutineScope = scope
        override val savedState: ScopeSavedState get() = error("Unused feature state")
        override val isClosed = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
    }
    val repository = ProfileResearchRepository(storage, facade, search, toggles, profile, attachments)
}

internal class ExecutionStorage : ResearchStorage {
    private val values = MutableStateFlow<List<ResearchSession>>(emptyList())
    override fun observe(): Flow<List<ResearchSession>> = values
    override suspend fun read(): List<ResearchSession> = values.value
    override suspend fun add(session: ResearchSession) {
        values.value += session
    }
    override suspend fun update(id: String, transform: (ResearchSession) -> ResearchSession) {
        values.value = values.value.map { if (it.id == id) transform(it) else it }
    }
}

internal class ExecutionSession(
    private val target: EngineTarget,
    id: String,
    private val beforeAcceptance: suspend (SessionRef) -> Unit,
) : ActiveSession {
    override val ref = SessionRef(target.engine, SessionSourceId("research-test"), id)
    override val route = ExecutionRoute(target.engine, target.binding, AuthSourceId("source"), AuthRevision.Known("1"))
    override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    var request: PromptRequest? = null
        private set
    var isClosed = false
        private set
    var cancellationOutcome: TurnOutcome = TurnOutcome.Cancelled
    private var items = emptyList<SessionItem>()
    private val events = MutableSharedFlow<SessionEvent>(replay = 16)
    private val history = object : SessionHistory {
        override suspend fun page(request: HistoryPageRequest): HistoryPage =
            HistoryPage(items, null, null, HistoryCheckpoint("checkpoint"), HistoryCoverage.Complete)

        override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = events
    }
    private val sender = object : SendsPrompts {
        override suspend fun send(request: PromptRequest): TurnId {
            beforeAcceptance(ref)
            this@ExecutionSession.request = request
            val turn = Turn(TurnId("turn-${ref.nativeId}"), request.id, target)
            state.value = ActiveSessionState.Running(turn)
            val message = SessionItem.Message(info("user", 0), MessageRole.User, request.parts)
            items = listOf(message)
            events.emit(SessionEvent.ItemUpserted(HistoryCheckpoint("checkpoint"), message))
            return turn.id
        }
    }
    private val canceller = object : CancelsTurns {
        override suspend fun cancel(turn: TurnId) {
            val current = state.value as ActiveSessionState.Running
            assertEquals(current.turn.id, turn)
            finish(cancellationOutcome)
        }
    }
    override val features: EngineFeatures = ExecutionFeatures(
        SendsPrompts to sender,
        SessionHistory to history,
        CancelsTurns to canceller,
    )

    suspend fun complete(url: String, body: String) {
        val call = ToolCallId("fetch-${ref.nativeId}")
        val source = buildJsonObject {
            put("url", url)
            put("title", "Source")
            put("content", body)
        }.toString()
        val output = listOf(
            SessionItem.ToolCall(info("call", 1), call, "web_fetch", "{}", ToolCallStatus.Succeeded),
            SessionItem.ToolResult(info("result", 2), call, listOf(ContentPart.Text(source))),
            SessionItem.Message(info("assistant", 3), MessageRole.Assistant, listOf(ContentPart.Text("Answer"))),
        )
        items += output
        output.forEach { events.emit(SessionEvent.ItemUpserted(HistoryCheckpoint("checkpoint"), it)) }
        finish(TurnOutcome.Completed)
    }

    fun finish(outcome: TurnOutcome) {
        val current = state.value as ActiveSessionState.Running
        state.value = ActiveSessionState.Ready(current.turn.copy(outcome = outcome))
    }

    override suspend fun close() {
        isClosed = true
    }

    private fun info(suffix: String, position: Long) = ItemInfo(ItemId("${ref.nativeId}-$suffix"), position, 0)
}

private class ExecutionFeatures(private vararg val entries: Pair<EngineFeatureKey<*>, EngineFeature>) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val entry = entries.firstOrNull { it.first.id == key.id && it.first.type == key.type }
            ?: return FeatureAccess.Unsupported
        @Suppress("UNCHECKED_CAST") // Both runtime feature type and its stable key are matched above.
        return FeatureAccess.Available(entry.second as F)
    }
}
