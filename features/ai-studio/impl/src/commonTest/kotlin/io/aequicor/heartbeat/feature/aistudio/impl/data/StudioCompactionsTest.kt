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
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StudioCompactionsTest {
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
    fun `a pending compaction of a studio chat is claimed, run as a turn and finished`() = runTest {
        val fixture = Fixture(this)
        val job = launch { fixture.compactions.run() }

        fixture.pending(Chat to PendingCompaction(CompactionMethod.Native, CompactionOrigin.Agent, "focus"))
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
        val registry = Registry { intent ->
            val ready = state.value as ContextOptimizationState.Ready
            if (intent is Public.Claim) {
                val claimed = ready.pending.getValue(intent.session)
                state.value = ready.copy(
                    pending = ready.pending - intent.session,
                    running = ready.running + (intent.session to claimed),
                )
            }
        }
        val executed = mutableListOf<StudioTurnRequest>()
        private val stores = ChecklistTestStores()
        private val host = RunHost(mutableListOf()).apply {
            execute = { request ->
                executed += request
                RunOutcome.Completed
            }
        }
        val compactions = StudioCompactions(
            stores,
            lazyOf(Runtime()),
            StudioRunCoordinator(RunProfile(scope), RunClock),
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
                    ),
                )
            }
        }

        fun pending(vararg entries: Pair<SessionRef, PendingCompaction>) {
            state.value = ContextOptimizationState.Ready(pending = entries.toMap())
        }
    }

    private class Registry(private val applied: (MachineIntent) -> Unit = {}) : MachineRegistry {
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
