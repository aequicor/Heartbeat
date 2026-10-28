package io.aequicor.heartbeat.feature.autocontinue.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueOutput
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueSettings
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoContinueSettingsStoreTest {
    @Test
    fun `controls wait for the settings and changes go to the machine`() = runTest {
        val fixture = Fixture(this, AutoContinueState.Loading)
        val screen = fixture.subscribe()
        runCurrent()
        assertFalse(screen.states.value.isLoaded)
        screen.intent(AutoContinueSettingsIntent.SetAutoTurnLimit(20))
        runCurrent()
        assertTrue(fixture.machine.sent.isEmpty())

        fixture.machine.state.value = AutoContinueState.Ready(AutoContinueSettings())
        runCurrent()
        assertTrue(screen.states.value.isLoaded)
        screen.intent(AutoContinueSettingsIntent.SetAutoTurnLimit(20))
        runCurrent()
        assertEquals(20, screen.states.value.autoTurnLimit)
        screen.intent(AutoContinueSettingsIntent.SetAutoTurnLimit(20))
        screen.intent(AutoContinueSettingsIntent.SetDefaultMode(DefaultModeUi.Suggest))
        runCurrent()
        assertEquals(
            listOf<AutoContinueIntent>(
                AutoContinueIntent.Public.UpdateSettings(AutoContinueSettings(autoTurnLimit = 20)),
                AutoContinueIntent.Public.UpdateSettings(
                    AutoContinueSettings(defaultMode = AutoContinueMode.Suggest, autoTurnLimit = 20),
                ),
            ),
            fixture.machine.sent,
        )
    }

    @Test
    fun `a failed save is shown until the next accepted change`() = runTest {
        val fixture = Fixture(this, AutoContinueState.Ready())
        val screen = fixture.subscribe()
        runCurrent()
        fixture.machine.outputs.emit(AutoContinueOutput.SaveFailed)
        runCurrent()
        assertTrue(screen.states.value.isSaveFailed)

        fixture.machine.result = SendResult.NotRunning
        screen.intent(AutoContinueSettingsIntent.SetOptionCount(2))
        runCurrent()
        assertTrue(screen.states.value.isSaveFailed)

        fixture.machine.result = SendResult.Accepted
        screen.intent(AutoContinueSettingsIntent.SetOptionCount(1))
        runCurrent()
        assertFalse(screen.states.value.isSaveFailed)
    }

    @Test
    fun `the profile default model is known only after the selection is read`() = runTest {
        val fixture = Fixture(this, AutoContinueState.Ready())
        assertFalse(fixture.model.current.value.isProfileDefaultKnown)
        val screen = fixture.subscribe()
        runCurrent()
        assertTrue(screen.states.value.isProfileDefaultKnown)
    }

    private class Fixture(private val scope: TestScope, initial: AutoContinueState) {
        val machine = FakeMachine(initial)
        val model = AutoContinueSettingsModel(
            machine,
            FakeSelections(),
            HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler))),
            scope.backgroundScope,
        )

        suspend fun subscribe(): Provider<AutoContinueSettingsScreenState, AutoContinueSettingsIntent, *> {
            val provider =
                CompletableDeferred<Provider<AutoContinueSettingsScreenState, AutoContinueSettingsIntent, *>>()
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

    private class FakeMachine(initial: AutoContinueState) :
        Machine<AutoContinueState, AutoContinueIntent, AutoContinueOutput> {
        override val name = "auto-continue"
        override val state = MutableStateFlow(initial)
        override val outputs = MutableSharedFlow<AutoContinueOutput>(extraBufferCapacity = 4)
        val sent = mutableListOf<AutoContinueIntent>()
        var result = SendResult.Accepted

        /** Applies settings changes like the machine, so the screen sees what it sent. */
        override suspend fun send(intent: AutoContinueIntent): SendResult {
            if (result != SendResult.Accepted) return result
            sent += intent
            val ready = state.value as? AutoContinueState.Ready
            if (intent is AutoContinueIntent.Public.UpdateSettings && ready != null) {
                state.value = ready.copy(settings = intent.settings)
            }
            return result
        }
    }

    private class FakeSelections : ModelSelections {
        private val selection = MutableStateFlow(ModelSelection())

        override fun observe() = selection

        override suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection =
            change(selection.value).also { selection.value = it }
    }

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
    }
}
