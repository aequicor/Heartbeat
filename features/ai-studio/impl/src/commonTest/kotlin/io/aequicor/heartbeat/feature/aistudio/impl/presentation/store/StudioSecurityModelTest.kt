package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityOutput
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

private typealias SecurityProvider = Provider<
    StudioSecurityScreenState,
    StudioSecurityScreenIntent,
    StudioSecurityScreenAction,
>

@OptIn(ExperimentalCoroutinesApi::class)
class StudioSecurityModelTest {
    private val machine = SecurityScreenMachine()

    @Test
    fun `screen sends selections without claiming they were saved`() = runTest {
        machine.state.value = StudioSecurityState.Ready(StudioSecurityLevel.Ask)
        val store = screen()
        store.intent(StudioSecurityScreenIntent.Select(StudioSecurityLevelUi.Trust))
        runCurrent()
        assertEquals<List<StudioSecurityIntent>>(
            listOf(StudioSecurityIntent.Public.SetLevel(StudioSecurityLevel.Trust)),
            machine.sent,
        )
        assertEquals(StudioSecurityLevelUi.Ask, store.states.value.level)

        machine.state.value = StudioSecurityState.Saving(StudioSecurityLevel.Ask, StudioSecurityLevel.Trust)
        runCurrent()
        assertTrue(store.states.value.isSaving)
        assertEquals(StudioSecurityLevelUi.Ask, store.states.value.level)
        machine.state.value = StudioSecurityState.Ready(StudioSecurityLevel.Ask, isSaveFailed = true)
        runCurrent()
        assertTrue(store.states.value.isSaveFailed)
        assertFalse(store.states.value.isSaving)
    }

    @Test
    fun `external profile changes remain visible while saving and after a failure`() = runTest {
        machine.state.value = StudioSecurityState.Ready(StudioSecurityLevel.Ask)
        val store = screen()
        machine.state.value = StudioSecurityState.Ready(StudioSecurityLevel.Trust)
        runCurrent()
        assertEquals(StudioSecurityLevelUi.Trust, store.states.value.level)
        machine.state.value = StudioSecurityState.Saving(StudioSecurityLevel.Automatic, StudioSecurityLevel.Ask)
        runCurrent()
        assertEquals(StudioSecurityLevelUi.Automatic, store.states.value.level)
        assertTrue(store.states.value.isSaving)
        machine.state.value = StudioSecurityState.Ready(StudioSecurityLevel.Automatic, isSaveFailed = true)
        runCurrent()
        assertEquals(StudioSecurityLevelUi.Automatic, store.states.value.level)
        assertTrue(store.states.value.isSaveFailed)
    }

    @Test
    fun `reopened screen reflects errors and retries the profile load`() = runTest {
        machine.state.value = StudioSecurityState.LoadError
        val store = screen()
        assertTrue(store.states.value.isLoadFailed)
        assertFalse(store.states.value.isLoaded)
        assertEquals(StudioSecurityLevelUi.Ask, store.states.value.level)
        store.intent(StudioSecurityScreenIntent.Retry)
        runCurrent()
        assertEquals<List<StudioSecurityIntent>>(listOf(StudioSecurityIntent.Public.Retry), machine.sent)
        machine.state.value = StudioSecurityState.Ready()
        runCurrent()
        assertFalse(store.states.value.isLoadFailed)
        assertTrue(store.states.value.isLoaded)
        assertEquals(StudioSecurityLevelUi.Automatic, store.states.value.level)
    }

    private suspend fun TestScope.screen(): SecurityProvider {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = HeartbeatStoreFactory(SecurityDispatchers(dispatcher))
        val model = StudioSecurityModel(machine, factory, backgroundScope)
        val screen = CompletableDeferred<SecurityProvider>()
        backgroundScope.launch {
            model.store.collect {
                screen.complete(this)
                awaitCancellation()
            }
        }
        runCurrent()
        return screen.await()
    }
}

private class SecurityScreenMachine : StudioSecurityMachine {
    override val name = "studio-security"
    override val state = MutableStateFlow<StudioSecurityState>(StudioSecurityState.Loading)
    override val outputs = MutableSharedFlow<StudioSecurityOutput>()
    val sent = mutableListOf<StudioSecurityIntent>()
    override suspend fun send(intent: StudioSecurityIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}

private class SecurityDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}
