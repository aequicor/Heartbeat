package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionStrategy
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationOutput
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain.ContextOptimizationMachine
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

private typealias SettingsProvider =
    Provider<ContextSettingsScreenState, ContextSettingsScreenIntent, ContextSettingsScreenAction>

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContextSettingsModelTest {
    private val machine = FakeMachine(ContextOptimizationState.Loading)

    @Test
    fun `changes are sent as whole settings once they are loaded`() = runTest {
        val store = screen()
        store.intent(ContextSettingsScreenIntent.SetThreshold(60))
        runCurrent()
        assertTrue(machine.sent.isEmpty(), "nothing is sent before the settings are known")

        machine.state.value = ContextOptimizationState.Ready(ContextOptimizationSettings())
        runCurrent()
        assertTrue(store.states.value.isLoaded)
        store.intent(ContextSettingsScreenIntent.SetStrategy(StrategyUi.Rollover))
        runCurrent()

        assertEquals<List<ContextOptimizationIntent>>(
            listOf(
                ContextOptimizationIntent.Public.UpdateSettings(
                    ContextOptimizationSettings(strategy = CompactionStrategy.Rollover),
                ),
            ),
            machine.sent,
        )
        assertFalse(store.states.value.isSaveFailed)
    }

    @Test
    fun `selecting the current value sends nothing and reports no failure`() = runTest {
        machine.state.value = ContextOptimizationState.Ready(ContextOptimizationSettings())
        val store = screen()

        store.intent(ContextSettingsScreenIntent.SetThreshold(ContextOptimizationSettings.DEFAULT_THRESHOLD_PERCENT))
        runCurrent()

        assertTrue(machine.sent.isEmpty())
        assertFalse(store.states.value.isSaveFailed)
    }

    @Test
    fun `only a real rejection shows that the settings were not saved`() = runTest {
        machine.state.value = ContextOptimizationState.Ready(ContextOptimizationSettings())
        val store = screen()

        machine.result = SendResult.Ignored
        store.intent(ContextSettingsScreenIntent.SetThreshold(60))
        runCurrent()
        assertFalse(store.states.value.isSaveFailed, "an ignored change is not a failure")

        machine.result = SendResult.NotRunning
        store.intent(ContextSettingsScreenIntent.SetThreshold(70))
        runCurrent()
        assertTrue(store.states.value.isSaveFailed)

        machine.result = SendResult.Accepted
        store.intent(ContextSettingsScreenIntent.SetThreshold(90))
        runCurrent()
        assertFalse(store.states.value.isSaveFailed)

        machine.outputs.emit(ContextOptimizationOutput.SaveFailed)
        runCurrent()
        assertTrue(store.states.value.isSaveFailed)
    }

    private suspend fun TestScope.screen(): SettingsProvider {
        val factory = HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val model = ContextSettingsModel(machine, factory, backgroundScope)
        val screen = CompletableDeferred<SettingsProvider>()
        backgroundScope.launch {
            model.store.collect {
                screen.complete(this)
                awaitCancellation()
            }
        }
        runCurrent()
        return screen.await()
    }

    private class FakeMachine(initial: ContextOptimizationState) : ContextOptimizationMachine {
        override val name = "context-optimization"
        override val state = MutableStateFlow(initial)
        override val outputs = MutableSharedFlow<ContextOptimizationOutput>(extraBufferCapacity = 8)
        val sent = mutableListOf<ContextOptimizationIntent>()
        var result = SendResult.Accepted

        override suspend fun send(intent: ContextOptimizationIntent): SendResult = result.also { sent += intent }
    }
}

private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}
