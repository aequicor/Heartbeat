package io.aequicor.heartbeat.feature.appupdate.impl.di

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateMachineSpec
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateOutput
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateState
import io.aequicor.heartbeat.feature.appupdate.api.InstallProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateStartupTest {
    private val release = AppRelease("1.4.0", "app.dmg", "https://github.com/app.dmg", "a".repeat(64), 100, "")

    @Test
    fun `disabling updates waits for installation failure then forgets the offer`() = runTest {
        val fixture = startInstalling()

        fixture.toggles.enabled.value = false
        runCurrent()
        assertTrue(fixture.machine.requests.isEmpty())
        fixture.machine.send(AppUpdateIntent.Internal.Progress(50, 100))
        runCurrent()
        assertTrue(fixture.machine.requests.isEmpty())

        fixture.machine.send(AppUpdateIntent.Internal.InstallFailed(AppUpdateFailure.Network))
        runCurrent()

        assertEquals(AppUpdateState.Inactive, fixture.machine.state.value)
        assertEquals(listOf<AppUpdateIntent.Public>(AppUpdateIntent.Public.Deactivate), fixture.machine.requests)
    }

    @Test
    fun `enabling updates cancels deferred deactivation`() = runTest {
        val fixture = startInstalling()
        fixture.toggles.enabled.value = false
        runCurrent()
        fixture.toggles.enabled.value = true
        runCurrent()

        fixture.machine.send(AppUpdateIntent.Internal.InstallFailed(AppUpdateFailure.Network))
        runCurrent()

        assertEquals(AppUpdateState.Available(release, AppUpdateFailure.Network), fixture.machine.state.value)
        assertTrue(fixture.machine.requests.isEmpty())
    }

    @Test
    fun `disabling updates never sends ignored intents while the installation restarts`() = runTest {
        val fixture = startInstalling()
        fixture.toggles.enabled.value = false
        runCurrent()

        fixture.machine.send(AppUpdateIntent.Internal.Launched)
        runCurrent()

        assertEquals(AppUpdateState.Restarting(release), fixture.machine.state.value)
        assertTrue(fixture.machine.requests.isEmpty())
    }

    private fun TestScope.startInstalling(): Fixture {
        val machine = UpdateMachine(AppUpdateState.Installing(release, InstallProgress.Downloading()))
        val toggles = Toggles()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
        }
        AppUpdateStartup(lazy { machine }, toggles, AppScope(backgroundScope), dispatchers).start()
        runCurrent()
        return Fixture(machine, toggles)
    }

    private data class Fixture(val machine: UpdateMachine, val toggles: Toggles)

    private class UpdateMachine(initial: AppUpdateState) : Machine<AppUpdateState, AppUpdateIntent, AppUpdateOutput> {
        override val name = "app-update"
        override val state = MutableStateFlow(initial)
        override val outputs: Flow<AppUpdateOutput> = emptyFlow()
        val requests = mutableListOf<AppUpdateIntent.Public>()

        override suspend fun send(intent: AppUpdateIntent): SendResult {
            if (intent is AppUpdateIntent.Public) requests += intent
            val resolution = AppUpdateMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
            state.value = resolution.to
            return SendResult.Accepted
        }
    }

    private class Toggles : FeatureToggles {
        val enabled = MutableStateFlow(true)

        // This fixture supplies only the Boolean app-update toggle.
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = enabled as Flow<T>

        // This fixture supplies only the Boolean app-update toggle.
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = enabled.value as T
    }

    private class AppScope(override val coroutineScope: CoroutineScope) : ScopeHandle {
        override val name = "test/app"
        override val savedState: ScopeSavedState get() = error("Unused")
        override val isClosed = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
    }
}
