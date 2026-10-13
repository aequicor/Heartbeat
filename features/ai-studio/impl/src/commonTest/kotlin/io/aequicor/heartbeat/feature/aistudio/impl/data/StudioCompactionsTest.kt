package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompactsContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionMethod
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionOrigin
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionResult
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionSupport
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent.Public
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContinuationBriefEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_SUMMARY_PROMPT
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.PendingCompaction
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StudioCompactionsTest {
    @Test
    fun `claimed compaction waits for originating settlement and preserves its receipt`() = runTest {
        val fixture = Fixture(this)
        val environment = object : StudioChatEnvironment {
            override fun bind(session: SessionRef): AutoCloseable = AutoCloseable { }
        }
        val source = runRequest("busy-browser").copy(environment = environment)
        val finish = CompletableDeferred<Unit>()
        fixture.onExecute = { request ->
            if (request.request == source.request) {
                finish.await()
                fixture.compactions.settled(request, Active(ContextUsage(1, 100)), TurnOutcome.Completed)
            }
        }
        val original = launch { fixture.runs.run(fixture.host, source) }
        runCurrent()
        val job = launch { fixture.compactions.run() }
        fixture.pending(
            Chat to PendingCompaction(
                CompactionMethod.Native,
                CompactionOrigin.Agent,
                sourceRequest = source.request,
            ),
        )
        runCurrent()
        assertTrue(Public.Claim(Chat) in fixture.registry.sent)
        assertEquals(listOf(source), fixture.executed)
        finish.complete(Unit)
        original.join()
        runCurrent()
        assertEquals(environment, fixture.executed.single { it.request.value.startsWith("continue-") }.environment)
        job.cancel()
    }

    @Test
    fun `agent continuations require the exact source receipt and retain its browser context`() = runTest {
        val fixture = Fixture(this)
        val environment = object : StudioChatEnvironment {
            override fun bind(session: SessionRef): AutoCloseable = AutoCloseable { }
        }
        val source = runRequest("browser-source").copy(environment = environment)
        val job = launch { fixture.compactions.run() }
        fixture.pending(
            Chat to PendingCompaction(
                CompactionMethod.Native,
                CompactionOrigin.Agent,
                sourceRequest = source.request,
            ),
        )
        fixture.compactions.settled(source, Active(ContextUsage(1, 100)), TurnOutcome.Completed)
        runCurrent()
        assertEquals(environment, fixture.executed.last().environment)
        assertTrue(fixture.executed.last().request.value.startsWith("continue-"))
        fixture.pending(
            Chat to PendingCompaction(
                CompactionMethod.Native,
                CompactionOrigin.Agent,
                sourceRequest = runRequest("other").request,
            ),
        )
        fixture.compactions.settled(source, Active(ContextUsage(1, 100)), TurnOutcome.Completed)
        runCurrent()
        assertEquals(1, fixture.executed.count { it.request.value.startsWith("continue-") })
        fixture.pending(Chat to PendingCompaction(CompactionMethod.Native, CompactionOrigin.Agent))
        runCurrent()
        assertEquals(1, fixture.executed.count { it.request.value.startsWith("continue-") })
        job.cancel()
    }

    @Test
    fun `a user request of a chat with a native session becomes a pending compaction`() = runTest {
        val fixture = Fixture(this)
        runCurrent()

        assertTrue(fixture.compactions.request("chat", "keep decisions"))
        assertFalse(fixture.compactions.request("new-chat", null))

        assertEquals<List<MachineIntent>>(
            listOf(Public.Compact(Chat, CompactionOrigin.User, "keep decisions")),
            fixture.registry.sent,
        )
    }

    @Test
    fun `helper and organism chats refuse a user compaction before it is queued`() = runTest {
        val fixture = Fixture(this)
        runCurrent()

        assertFalse(fixture.compactions.request("helper", null))
        assertFalse(fixture.compactions.request("organism", null))

        assertTrue(fixture.registry.sent.isEmpty())
    }

    @Test
    fun `a compaction queued while the previous one of the chat finishes still runs`() = runTest {
        val fixture = Fixture(this)
        var isQueuedAgain = false
        fixture.afterFinished = {
            if (!isQueuedAgain) {
                isQueuedAgain = true
                fixture.pending(Chat to PendingCompaction(CompactionMethod.Native, CompactionOrigin.User))
                // The host observes the new request while the finished one is still marked in flight.
                repeat(10) { yield() }
            }
        }
        val job = launch { fixture.compactions.run() }

        fixture.pending(Chat to PendingCompaction(CompactionMethod.Native, CompactionOrigin.User))
        runCurrent()

        assertEquals(2, fixture.executed.count { it.compaction != null })
        job.cancel()
    }

    @Test
    fun `a pending compaction of a studio chat is claimed, run as a turn and finished`() = runTest {
        val fixture = Fixture(this)
        val job = launch { fixture.compactions.run() }

        val source = runRequest("source")
        fixture.pending(
            Chat to PendingCompaction(CompactionMethod.Native, CompactionOrigin.Agent, "focus", source.request),
        )
        fixture.compactions.settled(source, Active(ContextUsage(1, 100)), TurnOutcome.Completed)
        fixture.registry.sent.clear()
        runCurrent()

        val (request, continuation) = fixture.executed
        assertEquals("chat", request.id)
        assertEquals("focus", request.compaction?.instructions)
        assertTrue(request.isBriefed)
        assertEquals("chat", continuation.id)
        assertTrue(continuation.compaction == null && continuation.directives.single().startsWith("The context"))
        assertEquals<List<MachineIntent>>(
            listOf(Public.Claim(Chat), Public.Finished(Chat, CompactionResult.Compacted)),
            fixture.registry.sent,
        )
        job.cancel()
    }

    @Test
    fun `an agent that keeps compacting is continued at most three times in a row`() = runTest {
        val fixture = Fixture(this)
        val job = launch { fixture.compactions.run() }

        repeat(4) {
            val source = runRequest("continue-source-$it")
            fixture.pending(
                Chat to PendingCompaction(
                    CompactionMethod.Native,
                    CompactionOrigin.Agent,
                    sourceRequest = source.request,
                ),
            )
            fixture.compactions.settled(source, Active(ContextUsage(1, 100)), TurnOutcome.Completed)
            runCurrent()
        }

        assertEquals(4, fixture.executed.count { it.compaction != null })
        assertEquals(3, fixture.executed.count { it.request.value.startsWith("continue-") })
        job.cancel()
    }

    @Test
    fun `only the agent's own compaction continues its work, and only with the brief`() = runTest {
        val user = Fixture(this)
        val userJob = launch { user.compactions.run() }
        user.pending(Chat to PendingCompaction(CompactionMethod.Native, CompactionOrigin.User))
        runCurrent()
        val disabled = Fixture(this, disabled = setOf(ContinuationBriefEnabled))
        val disabledJob = launch { disabled.compactions.run() }
        disabled.pending(Chat to PendingCompaction(CompactionMethod.Native, CompactionOrigin.Agent))
        runCurrent()

        assertTrue(user.executed.single().compaction != null)
        assertTrue(disabled.executed.single().let { it.compaction != null && !it.isBriefed })
        userJob.cancel()
        disabledJob.cancel()
    }

    @Test
    fun `unknown sessions finish as unsupported and a rollover runs as a reserved run`() = runTest {
        val fixture = Fixture(this, disabled = setOf(ContinuationBriefEnabled))
        val job = launch { fixture.compactions.run() }

        fixture.pending(
            Foreign to PendingCompaction(CompactionMethod.Native, CompactionOrigin.Auto),
            Chat to PendingCompaction(CompactionMethod.Rollover, CompactionOrigin.Auto),
        )
        runCurrent()

        val rollover = fixture.executed.single()
        assertTrue(rollover.isRollover && !rollover.isBriefed && rollover.compaction == null)
        assertEquals<Set<MachineIntent>>(
            setOf(
                Public.Claim(Foreign),
                Public.Finished(Foreign, CompactionResult.Unsupported),
                Public.Claim(Chat),
                Public.Finished(Chat, CompactionResult.Compacted),
            ),
            fixture.registry.sent.toSet(),
        )
        job.cancel()
    }

    @Test
    fun `a briefed rollover first runs the handoff summary turn without reporting it`() = runTest {
        val fixture = Fixture(this)
        val job = launch { fixture.compactions.run() }

        fixture.pending(Chat to PendingCompaction(CompactionMethod.Rollover, CompactionOrigin.Agent))
        runCurrent()

        val (summary, rollover) = fixture.executed
        assertTrue(summary.isHandoff && summary.directives == listOf(HANDOFF_SUMMARY_PROMPT))
        assertTrue(!summary.isRollover && summary.compaction == null)
        assertTrue(rollover.isRollover && rollover.isBriefed)
        fixture.compactions.settled(summary, Active(ContextUsage(90, 100)), TurnOutcome.Completed)
        assertEquals<List<MachineIntent>>(
            listOf(Public.Claim(Chat), Public.Finished(Chat, CompactionResult.Compacted)),
            fixture.registry.sent,
        )
        job.cancel()
    }

    @Test
    fun `a settled prompt turn reports its occupancy and support`() = runTest {
        val fixture = Fixture(this)
        val usage = ContextUsage(90, 100)

        runCurrent()
        fixture.compactions.settled(runRequest("prompt"), Active(usage), TurnOutcome.Completed)
        fixture.compactions.settled(
            runRequest("compact").copy(compaction = ContextCompaction()),
            Active(usage),
            TurnOutcome.Completed,
        )

        assertEquals<List<MachineIntent>>(
            listOf(Public.TurnSettled(Chat, usage, CompactionSupport(isNative = true, isRollover = true), true)),
            fixture.registry.sent,
        )
    }

    private class Active(usage: ContextUsage) : ActiveSession {
        override val ref = Chat
        override val route = ExecutionRoute(
            EngineId("pi"),
            EngineBindingId("b"),
            AuthSourceId("source"),
            AuthRevision.Known("r"),
        )
        override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
        override val features = object : EngineFeatures {
            @Suppress("UNCHECKED_CAST") // The fake answers only the two keys the host resolves.
            override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> = when (key) {
                SessionContextUsage -> FeatureAccess.Available(
                    object : SessionContextUsage {
                        override val state = MutableStateFlow<ContextUsage?>(usage)
                    } as F,
                )

                CompactsContext -> FeatureAccess.Available(
                    object : CompactsContext {
                        override val isInstructionsSupported = true
                    } as F,
                )

                else -> FeatureAccess.Unsupported
            }
        }

        override suspend fun close() = Unit
    }

    private class Fixture(scope: TestScope, disabled: Set<FeatureToggle<*>> = emptySet()) {
        private val state = MutableStateFlow<ContextOptimizationState>(ContextOptimizationState.Ready())
        var afterFinished: suspend () -> Unit = {}
        val registry = Registry { intent ->
            val ready = state.value as ContextOptimizationState.Ready
            if (intent is Public.Claim) {
                val claimed = ready.pending.getValue(intent.session)
                state.value = ready.copy(
                    pending = ready.pending - intent.session,
                    running = ready.running + (intent.session to claimed),
                )
            }
            if (intent is Public.Finished) afterFinished()
        }
        val executed = mutableListOf<StudioTurnRequest>()
        val runs = StudioRunCoordinator(RunProfile(scope), RunClock)
        var onExecute: suspend (StudioTurnRequest) -> Unit = {}
        private val stores = ChecklistTestStores()
        val host = RunHost(mutableListOf()).apply {
            execute = { request ->
                executed += request
                onExecute(request)
                RunOutcome.Completed
            }
        }
        val compactions = StudioCompactions(
            stores,
            lazyOf(Runtime()),
            runs,
            lazyOf(host),
            object : ContextOptimizationView {
                override val state = this@Fixture.state
            },
            registry,
            Toggles(disabled),
        )

        init {
            scope.launch {
                stores.keyValue(ChatSpec).set(
                    ChatsKey,
                    listOf(
                        StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, ref = Chat),
                        StudioChatRecord("new-chat", "New", Instant.DISTANT_PAST),
                        StudioChatRecord(
                            "helper",
                            "Helper",
                            Instant.DISTANT_PAST,
                            ref = Chat.copy(nativeId = "helper-native"),
                            helper = StudioHelperIdentity(ActionId("action"), "chat", Chat, TrustLevel.Ask),
                        ),
                        StudioChatRecord(
                            "organism",
                            "Organism",
                            Instant.DISTANT_PAST,
                            ref = Chat.copy(nativeId = "organism-native"),
                            organismId = "organism",
                        ),
                    ),
                )
            }
        }

        fun pending(vararg entries: Pair<SessionRef, PendingCompaction>) {
            state.value = ContextOptimizationState.Ready(pending = entries.toMap())
        }
    }

    private class Registry(private val applied: suspend (MachineIntent) -> Unit = {}) : MachineRegistry {
        val sent = mutableListOf<MachineIntent>()

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? = null

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ) = MutableStateFlow<MachineRef<S, P, O>?>(null)

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult {
            sent += intent
            applied(intent)
            return SendResult.Accepted
        }
    }

    private class Runtime : StudioRuntime {
        override val state = MutableStateFlow(StudioRuntimeState())
        override suspend fun defaults(): RunSettings = DefaultRunSettings
        override suspend fun run(sessionId: String, prompt: String, settings: RunSettings) = RunOutcome.Completed
        override suspend fun cancel(sessionId: String) = Unit
        override suspend fun respond(
            sessionId: String,
            requestId: String,
            optionId: String,
            answer: io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer?,
        ) = Unit
    }

    private class Toggles(private val disabled: Set<FeatureToggle<*>>) : FeatureToggles {
        override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(value(toggle))
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

        @Suppress("UNCHECKED_CAST") // The host reads only flags.
        private fun <T : Any> value(toggle: FeatureToggle<T>): T = (toggle !in disabled) as T
    }

    private companion object {
        val Chat = SessionRef(EngineId("pi"), SessionSourceId("pi.profile"), "chat-native")
        val Foreign = SessionRef(EngineId("pi"), SessionSourceId("pi.profile"), "other-native")
    }
}
