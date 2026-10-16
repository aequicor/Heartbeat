package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewLoad
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FacadeAgentSafetyReviewerTest {
    @Test
    fun `review uses a fresh isolated session and releases it after a complete verdict`() = runTest {
        val f = ReviewFixture(backgroundScope)
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "{\"source\":\"safe code\"}").isSafe)
        val request = f.creations.single()
        assertNull(request.workspace)
        assertFalse(request.areDetachedToolsEnabled)
        assertTrue(request.areSessionHooksEnabled)
        assertTrue(request.preselected.isEmpty())
        assertTrue(f.sawOffBeforeSend)
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref.copy(nativeId = "other"))))
        val text = (f.prompts.single().parts.single() as ContentPart.Text).text
        assertTrue(text.contains("Trusted host policy:\nBounded local changes only"))
        assertTrue(text.contains("Untrusted change (JSON):\n{\"source\":\"safe code\"}"))
    }

    @Test
    fun `provider failure is uncertain without source leakage and releases the reviewer slot`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { failsOnCreate = true }
        val review = f.reviewer.review(f.target, "Bounded local changes only", "{}")
        assertFalse(review.isSafe)
        assertFalse(review.reason.contains("private source"))
        f.failsOnCreate = false
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
    }

    @Test
    fun `failed submission reconciles an idle session and releases the reviewer slot`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { failsOnSend = true }
        val review = f.reviewer.review(f.target, "Bounded local changes only", "{}")
        assertFalse(review.isSafe)
        assertFalse(review.reason.contains("private source"))
        assertEquals(1, f.reconciles)
        assertEquals(0, f.cancels)
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        f.failsOnSend = false
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "second").isSafe)
        assertEquals(2, f.creations.size)
    }

    @Test
    fun `reconciliation revealing an active review cancels it before releasing the slot`() = runTest {
        val f = ReviewFixture(backgroundScope).apply {
            failsOnSend = true
            remainsActiveAfterReconcile = true
        }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        assertEquals(1, f.reconciles)
        assertEquals(1, f.cancels)
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        f.failsOnSend = false
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "second").isSafe)
    }

    @Test
    fun `failed reconciliation retains an unresolved review and its restrictions`() = runTest {
        val f = ReviewFixture(backgroundScope).apply {
            failsOnSend = true
            failsOnReconcile = true
        }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        assertEquals(1, f.reconciles)
        assertEquals(0, f.cancels)
        assertFalse(f.closed)
        assertFalse(f.archived)
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "second").isSafe)
        assertEquals(1, f.creations.size)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
        assertEquals(AgentSafetyReviewLoad(capacity = 1, finishing = 1), f.slots.load.value)
    }

    @Test
    fun `terminal unavailable session releases the slot without unnecessary recovery`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { finishesUnavailable = true }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        assertEquals(0, f.reconciles)
        assertEquals(0, f.cancels)
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        f.finishesUnavailable = false
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "second").isSafe)
    }

    @Test
    fun `late unavailable outcome releases the retained review only for its own turn`() = runTest {
        val f = ReviewFixture(backgroundScope).apply {
            failsOnSend = true
            failsOnReconcile = true
        }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        f.finish(TurnId("unrelated"), isUnavailable = true)
        runCurrent()
        assertFalse(f.closed)
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "second").isSafe)
        assertEquals(1, f.creations.size)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
        f.finish(isUnavailable = true)
        runCurrent()
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        f.failsOnSend = false
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "third").isSafe)
        assertEquals(2, f.creations.size)
    }

    @Test
    fun `detached handle does not prove an unresolved review has stopped`() = runTest {
        val f = ReviewFixture(backgroundScope).apply {
            failsOnSend = true
            failsOnReconcile = true
        }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        f.detach()
        runCurrent()
        assertFalse(f.archived)
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "second").isSafe)
        assertEquals(1, f.creations.size)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
    }

    @Test
    fun `review timeout cancels closes and returns uncertainty`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { outcome = null }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        assertEquals(1, f.cancels)
        assertTrue(f.closed)
    }

    @Test
    fun `unknown cancellation holds the only reviewer slot and restrictions within the grace period`() = runTest {
        val f = ReviewFixture(backgroundScope).apply {
            outcome = null
            ignoresCancel = true
        }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        assertFalse(f.closed)
        val refused = f.reviewer.review(f.target, "Bounded local changes only", "second")
        assertFalse(refused.isSafe)
        assertTrue("Выполняется 1 из 1, в очереди 0." in refused.reason)
        assertEquals(1, f.creations.size)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
    }

    @Test
    fun `a running review ignoring cancellation is closed and abandoned after the grace period`() = runTest {
        val f = ReviewFixture(backgroundScope).apply {
            outcome = null
            ignoresCancel = true
        }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        assertEquals(AgentSafetyReviewLoad(capacity = 1, finishing = 1), f.slots.load.value)
        advanceTimeBy(GRACE + 1.seconds)
        assertEquals(AgentSafetyReviewLoad(capacity = 1, abandoned = 1), f.slots.load.value)
        assertTrue(f.closed)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
    }

    @Test
    fun `late terminal acknowledgement closes the retained session and allows the next review`() = runTest {
        val f = ReviewFixture(backgroundScope).apply {
            outcome = null
            ignoresCancel = true
        }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        f.finish(TurnId("unrelated"))
        runCurrent()
        assertFalse(f.closed)
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "second").isSafe)
        assertEquals(1, f.creations.size)
        f.finish()
        runCurrent()
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
        f.outcome = TurnOutcome.Completed
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "third").isSafe)
        assertEquals(2, f.creations.size)
    }

    @Test
    fun `profile shutdown closes the retained reviewer handle`() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
        val f = ReviewFixture(owner).apply {
            outcome = null
            ignoresCancel = true
        }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        assertFalse(f.closed)
        owner.cancel()
        runCurrent()
        assertTrue(f.closed)
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "second").isSafe)
        assertEquals(1, f.creations.size)
    }

    @Test
    fun `unresponsive close is bounded and releases the slot as abandoned`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { hangsOnClose = true }
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        assertFalse(f.closed)
        assertEquals(1, f.slots.load.value.finishing)
        advanceTimeBy(30.seconds)
        assertEquals(AgentSafetyReviewLoad(capacity = 1, abandoned = 1), f.slots.load.value)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
        f.hangsOnClose = false
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "second").isSafe)
        assertEquals(2, f.creations.size)
    }

    @Test
    fun `a handle whose close failed is closed again when the profile closes`() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
        val f = ReviewFixture(owner).apply { hangsOnClose = true }
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        advanceTimeBy(30.seconds)
        assertEquals(AgentSafetyReviewLoad(capacity = 1, abandoned = 1), f.slots.load.value)
        assertFalse(f.closed)
        f.hangsOnClose = false
        owner.cancel()
        runCurrent()
        assertTrue(f.closed)
    }

    @Test
    fun `a review leaving the queue after the profile started closing opens no session`() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
        val gate = CompletableDeferred<Unit>()
        val f = ReviewFixture(owner).apply { createGate = gate }
        val first = async { f.reviewer.review(f.target, "Bounded local changes only", "first") }
        val second = async { f.reviewer.review(f.target, "Bounded local changes only", "second") }
        runCurrent()
        owner.cancel()
        gate.complete(Unit)
        first.await()
        val refused = second.await()
        assertFalse(refused.isSafe)
        assertTrue("Профиль закрывается" in refused.reason)
        assertEquals(1, f.creations.size)
    }

    @Test
    fun `unresolved review releases its slot after the grace period and keeps its restrictions`() = runTest {
        val f = ReviewFixture(backgroundScope).apply {
            failsOnSend = true
            failsOnReconcile = true
        }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        assertEquals(AgentSafetyReviewLoad(capacity = 1, finishing = 1), f.slots.load.value)
        advanceTimeBy(GRACE + 11.seconds)
        assertEquals(AgentSafetyReviewLoad(capacity = 1, abandoned = 1), f.slots.load.value)
        assertTrue(f.closed)
        assertFalse(f.archived)
        assertEquals(ToolSwitch.Off, f.policy.policy(ToolPolicyScope(session = f.ref))?.native?.get("shell"))
        f.failsOnSend = false
        assertTrue(f.reviewer.review(f.target, "Bounded local changes only", "second").isSafe)
        assertEquals(2, f.creations.size)
    }

    @Test
    fun `a review queued behind a running one waits for its slot instead of refusing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = ReviewFixture(backgroundScope).apply { createGate = gate }
        val first = async { f.reviewer.review(f.target, "Bounded local changes only", "first") }
        val second = async { f.reviewer.review(f.target, "Bounded local changes only", "second") }
        runCurrent()
        assertEquals(AgentSafetyReviewLoad(capacity = 1, running = 1, queued = 1), f.slots.load.value)
        gate.complete(Unit)
        assertTrue(first.await().isSafe)
        assertTrue(second.await().isSafe)
        assertEquals(2, f.creations.size)
        assertTrue(f.slots.load.value.isIdle)
    }

    @Test
    fun `a permission request is never answered or interpreted as a safe verdict`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { asks = true }
        assertFalse(f.reviewer.review(f.target, "Bounded local changes only", "{}").isSafe)
        assertEquals(1, f.cancels)
        assertTrue(f.closed)
    }

    @Test
    fun `cancelling the parent cleans up and propagates`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { outcome = null }
        val task = async { f.reviewer.review(f.target, "Bounded local changes only", "{}") }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(f.closed)
        assertEquals(1, f.cancels)
    }
}

private val GRACE = 1.minutes

/** Shared by the reviewer and isolated prompt tests: one fake engine, session and isolation policy. */
internal class ReviewFixture(scope: CoroutineScope) {
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
        override val ref = this@ReviewFixture.ref
        override val route: ExecutionRoute get() = error("unused")
        override val state = this@ReviewFixture.state
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
    val slots = AgentSafetyReviewSlots(capacity = 1, queueWait = 10.seconds)
    val reviewer = FacadeAgentSafetyReviewer(lazyOf(facade), policy, slots, scope, GRACE)

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
