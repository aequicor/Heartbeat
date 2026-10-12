package io.aequicor.heartbeat.feature.harness.impl.data.authoring

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
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacadeHarnessCodeReviewerTest {
    @Test
    fun `review uses a fresh isolated session and releases it after a complete verdict`() = runTest {
        val f = ReviewFixture()
        assertTrue(f.reviewer.review(f.target, "{\"source\":\"safe code\"}").isSafe)
        val request = f.creations.single()
        assertNull(request.workspace)
        assertFalse(request.areDetachedToolsEnabled)
        assertTrue(request.areSessionHooksEnabled)
        assertTrue(f.sawOffBeforeSend)
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref.copy(nativeId = "other"))))
    }

    @Test
    fun `review timeout cancels closes and returns uncertainty`() = runTest {
        val f = ReviewFixture().apply { outcome = null }
        assertFalse(f.reviewer.review(f.target, "{}").isSafe)
        assertEquals(1, f.cancels)
        assertTrue(f.closed)
    }

    @Test
    fun `unknown cancellation keeps native restrictions even after closing the handle`() = runTest {
        val f = ReviewFixture().apply {
            outcome = null
            ignoresCancel = true
        }
        assertFalse(f.reviewer.review(f.target, "{}").isSafe)
        assertTrue(f.closed)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
    }

    @Test
    fun `a permission request is never answered or interpreted as a safe verdict`() = runTest {
        val f = ReviewFixture().apply { asks = true }
        assertFalse(f.reviewer.review(f.target, "{}").isSafe)
        assertEquals(1, f.cancels)
        assertTrue(f.closed)
    }

    @Test
    fun `cancelling the parent cleans up and propagates`() = runTest {
        val f = ReviewFixture().apply { outcome = null }
        val task = async { f.reviewer.review(f.target, "{}") }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(f.closed)
        assertEquals(1, f.cancels)
    }
}

private class ReviewFixture {
    val target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("model"))
    val ref = SessionRef(target.engine, SessionSourceId("source"), "review")
    val policy = HarnessReviewToolPolicy()
    var sawOffBeforeSend = false
    var closed = false
    var archived = false
    var cancels = 0
    var outcome: TurnOutcome? = TurnOutcome.Completed
    var ignoresCancel = false
    var asks = false
    val creations = mutableListOf<CreateSessionRequest>()
    private val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    private val turn = TurnId("review-turn")
    private val sends = object : SendsPrompts {
        override suspend fun send(request: PromptRequest): TurnId {
            sawOffBeforeSend = policy.policy(ToolPolicyScope(session = ref))?.native?.get("shell") == ToolSwitch.Off
            assertEquals(TrustLevel.Ask, request.trust)
            val current = Turn(turn, request.id, target)
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

                outcome != null -> ActiveSessionState.Ready(current.copy(outcome = outcome))

                else -> ActiveSessionState.Running(current)
            }
            return turn
        }
    }
    private val cancel = object : CancelsTurns {
        override suspend fun cancel(turn: TurnId) {
            cancels++
            if (!ignoresCancel) state.value = ActiveSessionState.Ready(Turn(turn, null, target, TurnOutcome.Cancelled))
        }
    }
    private val history = object : SessionHistory {
        override suspend fun page(request: HistoryPageRequest) = HistoryPage(
            listOf(
                SessionItem.Message(
                    ItemInfo(ItemId("answer"), 0, 0, turn),
                    MessageRole.Assistant,
                    listOf(ContentPart.Text("""{"verdict":"safe","reason":"Only formats JSON"}""")),
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
        override val ref = this@ReviewFixture.ref
        override val route: ExecutionRoute get() = error("unused")
        override val state = this@ReviewFixture.state
        override val features = reviewFeatures(
            mapOf(
                SendsPrompts to sends,
                CancelsTurns to cancel,
                SessionHistory to history,
            ),
        )
        override suspend fun close() {
            closed = true
            state.value = ActiveSessionState.Closed
        }
    }
    private val creates = object : CreatesSessions {
        override suspend fun create(request: CreateSessionRequest): ActiveSession {
            creations += request
            return session
        }
    }
    private val facade = object : EngineFacade {
        override val engines = object : EngineCatalog {
            override val state = MutableStateFlow(
                listOf(
                    EngineInfo(
                        EngineDescriptor(
                            target.engine,
                            "Test",
                            EngineFamily.BuiltIn,
                            emptySet(),
                            HarnessEnabled,
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
                                this@ReviewFixture.archived = archived
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
    val reviewer = FacadeHarnessCodeReviewer(lazyOf(facade), policy)
}

private fun reviewFeatures(entries: Map<EngineFeatureKey<*>, EngineFeature>) = object : EngineFeatures {
    @Suppress("UNCHECKED_CAST") // Test registrations pair each feature with its own key.
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
        (entries[key] as F?)?.let { FeatureAccess.Available(it) } ?: FeatureAccess.Unsupported
}
