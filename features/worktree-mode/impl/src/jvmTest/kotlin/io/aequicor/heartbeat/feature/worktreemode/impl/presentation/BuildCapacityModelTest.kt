package io.aequicor.heartbeat.feature.worktreemode.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.worktreemode.impl.data.BuildBudget
import io.aequicor.heartbeat.feature.worktreemode.impl.data.BuildCapacity
import io.aequicor.heartbeat.feature.worktreemode.impl.data.BuildCapacitySettings
import io.aequicor.heartbeat.feature.worktreemode.impl.data.SettingsStores
import io.aequicor.heartbeat.feature.worktreemode.impl.data.StoredBuildLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias CapacityProvider =
    Provider<BuildCapacityScreenState, BuildCapacityScreenIntent, BuildCapacityScreenAction>

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BuildCapacityModelTest {
    private val settings = BuildCapacitySettings(lazy { SettingsStores() })

    @Test
    fun `screen shows the automatic estimate and stores a chosen limit`() = runTest {
        val store = screen()
        assertTrue(store.states.value.isLoaded)
        assertEquals(3, store.states.value.automaticBuilds)
        assertNull(store.states.value.limit)

        store.intent(BuildCapacityScreenIntent.SetLimit(2))
        runCurrent()
        assertEquals(2, settings.observe().first())
        assertEquals(2, store.states.value.limit)

        store.intent(BuildCapacityScreenIntent.SetLimit(null))
        runCurrent()
        assertNull(settings.observe().first())
        assertNull(store.states.value.limit)
        assertFalse(store.states.value.isSaveFailed)
    }

    private suspend fun TestScope.screen(): CapacityProvider {
        val factory = HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(testScheduler)))
        val capacity = object : BuildCapacity {
            override fun budget(): BuildBudget = BuildBudget.parallel(3)
        }
        val model = BuildCapacityModel(StoredBuildLimits(settings, capacity), factory, backgroundScope)
        val screen = CompletableDeferred<CapacityProvider>()
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

private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}
