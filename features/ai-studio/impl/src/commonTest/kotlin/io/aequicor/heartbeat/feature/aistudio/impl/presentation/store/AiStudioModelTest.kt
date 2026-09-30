package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.TestClock
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoice
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationMachineKey
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AiStudioModelTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, sessionId = "s-facade")),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = "p-heartbeat",
    )

    @Test
    fun `machine phases and panes are reflected with the open transcripts`() = runTest {
        val fixture = Fixture(this, AiStudioState.Loading)
        val screen = fixture.subscribe()
        assertEquals(StudioPhase.Loading, screen.states.value.phase)

        fixture.machine.state.value = ready
        runCurrent()
        val state = screen.states.value
        assertEquals(StudioPhase.Ready, state.phase)
        assertEquals(listOf(PaneUi(0, sessionId = "s-facade")), state.panes)
        assertEquals(listOf("s-facade"), state.transcripts.keys.toList())
        assertEquals(4, state.transcripts.getValue("s-facade").size)
        assertEquals("heartbeat", state.projects.first().name)
        assertEquals(AiStudioIntent.Public.Start, fixture.machine.sent.first())
    }

    @Test
    fun `submit forwards the draft and clears it only after the machine accepts it`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.DraftChanged(0, "Next step"))
        fixture.machine.result = SendResult.Ignored
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()
        assertEquals("Next step", screen.states.value.draft(0))

        fixture.machine.result = SendResult.Accepted
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()
        assertEquals("", screen.states.value.draft(0))
        assertEquals(AiStudioIntent.Public.Submit(0, "Next step"), fixture.machine.sent.last())
    }

    @Test
    fun `a failed submit restores the prompt into its composer`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()
        fixture.machine.outputs.emit(AiStudioOutput.SubmitFailed(0, "Lost prompt"))
        runCurrent()
        assertEquals("Lost prompt", screen.states.value.draft(0))
        assertTrue(0 in screen.states.value.failedPanes)
    }

    @Test
    fun `preferences are replaced as a whole and session actions become edits`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.SelectEffort(EffortUi.Low))
        fixture.model.store.intent(AiStudioScreenIntent.SetPinned("s-adr", true))
        runCurrent()
        assertEquals(
            listOf(
                AiStudioIntent.Public.UpdateSettings(DefaultRunSettings.copy(effort = ReasoningEffort.Low)),
                AiStudioIntent.Public.Edit("s-adr", SessionEdit.SetPinned(true)),
            ),
            fixture.machine.sent.filterNot { it is AiStudioIntent.Public.ObserveUsageTargets }.drop(1),
        )
    }

    @Test
    fun `native effort selection goes to the effort machine and its choices are mirrored`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()
        val target = EngineTarget(EngineId("engine"), EngineBindingId("route"), ModelId("model"))
        val id = target.studioModelId()
        fixture.model.store.intent(AiStudioScreenIntent.SelectEngineEffort(id, "future"))
        fixture.model.store.intent(AiStudioScreenIntent.SelectEngineEffort("scripted-model", "low"))
        runCurrent()
        assertEquals<Any>(
            listOf<Any>(EffortConfigurationIntent.Public.Select(target, "future")),
            fixture.efforts.sent,
        )
        fixture.efforts.state.value = EffortConfigurationState.Ready(listOf(EffortChoice(target, "future")))
        runCurrent()
        assertEquals(mapOf(id to "future"), screen.states.value.settings.engineEfforts)
        fixture.model.store.intent(AiStudioScreenIntent.SelectEngineEffort(id, null))
        runCurrent()
        assertEquals(EffortConfigurationIntent.Public.Select(target, null), fixture.efforts.sent.last())
    }

    @Test
    fun `renaming sends only a changed title`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.StartRename("s-adr", "recent:s-adr"))
        fixture.model.store.intent(AiStudioScreenIntent.CommitRename)
        fixture.model.store.intent(AiStudioScreenIntent.StartRename("s-adr", "recent:s-adr"))
        fixture.model.store.intent(AiStudioScreenIntent.RenameChanged("ADR review"))
        fixture.model.store.intent(AiStudioScreenIntent.CommitRename)
        runCurrent()
        assertEquals(
            listOf<AiStudioIntent>(AiStudioIntent.Public.Edit("s-adr", SessionEdit.Rename("ADR review"))),
            fixture.machine.sent.filterNot { it is AiStudioIntent.Public.ObserveUsageTargets }.drop(1),
        )
        assertEquals(null, screen.states.value.sidebar.renaming)
    }

    @Test
    fun `the elapsed clock ticks each second only while a run is active`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()
        val idle = screen.states.value.now
        advanceTimeBy(3.seconds)
        assertEquals(idle, screen.states.value.now)

        fixture.machine.state.value = ready.copy(running = setOf("s-facade"))
        runCurrent()
        val started = screen.states.value.now
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(started + 2.seconds, screen.states.value.now)
    }

    @Test
    fun `idle calendar refreshes without a running agent`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()
        val initial = screen.states.value.now
        advanceTimeBy(61.seconds)
        runCurrent()
        assertEquals(initial + 60.seconds, screen.states.value.now)
    }

    @Test
    fun `research availability follows its entry service across machine updates`() = runTest {
        val fixture = Fixture(this, ready)
        val screen = fixture.subscribe()
        assertEquals(false, screen.states.value.isResearchEnabled)
        fixture.isResearchEnabled.value = true
        runCurrent()
        assertTrue(screen.states.value.isResearchEnabled)
        fixture.machine.state.value = ready.copy(panes = listOf(StudioPane(1)), focusedPaneId = 1)
        runCurrent()
        assertTrue(screen.states.value.isResearchEnabled)
        fixture.isResearchEnabled.value = false
        runCurrent()
        assertEquals(false, screen.states.value.isResearchEnabled)
    }

    @Test
    fun `accepted navigation closes the drawer and closed panes forget drafts`() = runTest {
        val fixture = Fixture(this, ready.copy(panes = ready.panes + StudioPane(1)))
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.SetDrawerOpen(true))
        fixture.model.store.intent(AiStudioScreenIntent.DraftChanged(1, "Draft"))
        fixture.model.store.intent(AiStudioScreenIntent.OpenSession("s-adr"))
        fixture.model.store.intent(AiStudioScreenIntent.ClosePane(1))
        runCurrent()
        assertEquals(false, screen.states.value.sidebar.isDrawerOpen)
        assertEquals("", screen.states.value.draft(1))
    }

    private class Fixture(private val scope: TestScope, initial: AiStudioState) {
        val machine = FakeMachine(initial)
        val efforts = FakeEfforts()
        val isResearchEnabled = MutableStateFlow(false)
        val model = AiStudioModel(
            machine = machine,
            backend = object : StudioBackend {
                private val repository = InMemoryStudioRepository(TestClock(scope))

                override suspend fun repository(): StudioRepository = repository

                override suspend fun effects(): EffectHandler<AiStudioEffect, AiStudioIntent> = EffectHandler.None
            },
            clock = TestClock(scope),
            scope = TestScopeHandle(scope.backgroundScope),
            factory = HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler))),
            entries = object : StudioEntries {
                override val showsResearch = isResearchEnabled
                override val showsConnections = flowOf(false)
                override val showsProfileSettings = flowOf(false)
            },
            efforts = efforts,
            machines = efforts,
        )

        suspend fun subscribe(): Provider<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction> {
            val provider =
                CompletableDeferred<Provider<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>>()
            scope.backgroundScope.launch {
                model.store.collect {
                    provider.complete(this)
                    awaitCancellation()
                }
            }
            scope.runCurrent()
            return provider.await()
        }
    }
}

private class FakeMachine(initial: AiStudioState) : Machine<AiStudioState, AiStudioIntent, AiStudioOutput> {
    override val name = "ai_studio"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<AiStudioOutput>(extraBufferCapacity = 8)
    val sent = mutableListOf<AiStudioIntent>()
    var result = SendResult.Accepted

    override suspend fun send(intent: AiStudioIntent): SendResult {
        if (result == SendResult.Accepted || intent == AiStudioIntent.Public.Start) sent += intent
        return if (intent == AiStudioIntent.Public.Start) SendResult.Accepted else result
    }
}

private class FakeEfforts :
    EffortChoicesView,
    MachineRegistry {
    override val state = MutableStateFlow<EffortConfigurationState>(EffortConfigurationState.Ready())
    val sent = mutableListOf<Any>()

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = null

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(null)

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult {
        assertEquals(EffortConfigurationMachineKey.name, key.name)
        sent += intent
        return SendResult.Accepted
    }
}

private class TestDispatchers(dispatcher: kotlinx.coroutines.CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}

private class TestScopeHandle(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test/aistudio"
    override val isClosed = false
    override val savedState = object : ScopeSavedState {
        override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
        override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
        override fun unregister(key: String) = Unit
        override fun snapshot() = SavedBundle(emptyMap())
    }

    override fun onClose(action: () -> Unit) = DisposableHandle { }
}
