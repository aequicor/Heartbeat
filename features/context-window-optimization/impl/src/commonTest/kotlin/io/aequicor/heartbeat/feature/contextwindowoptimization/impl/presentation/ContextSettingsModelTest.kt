package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionStrategy
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private typealias SettingsProvider =
    Provider<ContextSettingsScreenState, ContextSettingsScreenIntent, ContextSettingsScreenAction>

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContextSettingsModelTest {
    @Test
    fun `changes are sent as whole settings once they are loaded`() = runTest {
        val state = MutableStateFlow<ContextOptimizationState>(ContextOptimizationState.Loading)
        val registry = Registry()
        val view = object : ContextOptimizationView {
            override val state = state
        }
        val model = ContextSettingsModel(view, registry, factory(), backgroundScope)

        val screen = CompletableDeferred<SettingsProvider>()
        backgroundScope.launch {
            model.store.collect {
                screen.complete(this)
                awaitCancellation()
            }
        }
        runCurrent()
        val store = screen.await()
        store.intent(ContextSettingsScreenIntent.SetThreshold(60))
        runCurrent()
        assertTrue(registry.sent.isEmpty(), "nothing is sent before the settings are known")

        state.value = ContextOptimizationState.Ready(ContextOptimizationSettings())
        runCurrent()
        store.intent(ContextSettingsScreenIntent.SetStrategy(StrategyUi.Rollover))
        runCurrent()

        assertEquals<List<MachineIntent>>(
            listOf(
                ContextOptimizationIntent.Public.UpdateSettings(
                    ContextOptimizationSettings(strategy = CompactionStrategy.Rollover),
                ),
            ),
            registry.sent,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.factory() =
        HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(testScheduler)))

    private class Registry : MachineRegistry {
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
            return SendResult.Accepted
        }
    }
}

private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}
