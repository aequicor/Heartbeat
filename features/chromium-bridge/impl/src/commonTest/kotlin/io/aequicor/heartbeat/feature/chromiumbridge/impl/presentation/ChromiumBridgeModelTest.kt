package io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.chromiumbridge.api.BridgeServerStatus
import io.aequicor.heartbeat.feature.chromiumbridge.api.BrowserHost
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeIntent
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeOutput
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeState
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ConnectedBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistration
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistrationFailure
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
import kotlin.test.assertNull

private typealias BridgeProvider =
    Provider<ChromiumBridgeScreenState, ChromiumBridgeScreenIntent, ChromiumBridgeScreenAction>

class ChromiumBridgeModelTest {
    private val enabled = ChromiumBridgeState.Enabled(
        server = BridgeServerStatus.Listening,
        hosts = listOf(
            BrowserHost(ChromiumBrowser.Chrome, isDetected = true, HostRegistration.Current),
            BrowserHost(ChromiumBrowser.Edge, isDetected = false, HostRegistration.NotRegistered),
            BrowserHost(ChromiumBrowser.Brave, isDetected = false, HostRegistration.OtherBuild),
        ),
        connections = listOf(ConnectedBrowser(3, "Google Chrome", "140", "0.1.0", isAgentControlAllowed = true)),
        controlledTabs = 2,
        registering = ChromiumBrowser.Chrome,
        isAgentControlEnabled = true,
        extensionFolder = "/app/chromium-plugin",
    )

    @Test
    fun `the screen mirrors the bridge and hides browsers that are neither installed nor registered`() = runTest {
        val fixture = Fixture(this, enabled)

        val state = fixture.subscribe().states.value

        assertEquals(AvailabilityUi.Enabled, state.availability)
        assertEquals(ServerUi.Listening, state.server)
        assertEquals(
            listOf(
                BrowserRow(BrowserUi.Chrome, true, RegistrationUi.Current),
                BrowserRow(BrowserUi.Brave, false, RegistrationUi.OtherBuild),
            ),
            state.browsers,
        )
        assertEquals(BrowserUi.Chrome, state.busy)
        assertEquals(listOf(ConnectionRow(3, "Google Chrome", "140", "0.1.0", true)), state.connections)
        assertEquals(2, state.controlledTabs)
        assertEquals("/app/chromium-plugin", state.extensionFolder)
    }

    @Test
    fun `off and unsupported bridges are told apart`() = runTest {
        val fixture = Fixture(this, ChromiumBridgeState.Disabled(isSupported = true))
        val screen = fixture.subscribe()
        assertEquals(AvailabilityUi.Disabled, screen.states.value.availability)

        fixture.machine.state.value = ChromiumBridgeState.Disabled(isSupported = false)
        runCurrent()

        assertEquals(AvailabilityUi.Unsupported, screen.states.value.availability)
    }

    @Test
    fun `commands reach the machine`() = runTest {
        val fixture = Fixture(this, enabled)
        val screen = fixture.subscribe()

        screen.intent(ChromiumBridgeScreenIntent.Register(BrowserUi.Edge))
        screen.intent(ChromiumBridgeScreenIntent.Unregister(BrowserUi.Chrome))
        screen.intent(ChromiumBridgeScreenIntent.Rescan)
        screen.intent(ChromiumBridgeScreenIntent.RevokeTabs)
        screen.intent(ChromiumBridgeScreenIntent.RevealExtension)
        runCurrent()

        assertEquals<List<ChromiumBridgeIntent>>(
            listOf(
                ChromiumBridgeIntent.Public.Register(ChromiumBrowser.Edge),
                ChromiumBridgeIntent.Public.Unregister(ChromiumBrowser.Chrome),
                ChromiumBridgeIntent.Public.Rescan,
                ChromiumBridgeIntent.Public.RevokeTabs,
                ChromiumBridgeIntent.Public.RevealExtension,
            ),
            fixture.machine.sent,
        )
    }

    @Test
    fun `a failed registration is shown until dismissed or retried`() = runTest {
        val fixture = Fixture(this, enabled)
        val screen = fixture.subscribe()

        fixture.machine.outputs.emit(
            ChromiumBridgeOutput.RegistrationFailed(ChromiumBrowser.Edge, HostRegistrationFailure.Translocated),
        )
        runCurrent()
        assertEquals(RegistrationError(BrowserUi.Edge, FailureUi.Translocated), screen.states.value.error)

        screen.intent(ChromiumBridgeScreenIntent.DismissError)
        runCurrent()
        assertNull(screen.states.value.error)
    }

    private class Fixture(private val scope: TestScope, initial: ChromiumBridgeState) {
        val machine = FakeBridgeMachine(initial)
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val model = ChromiumBridgeModel(
            machine,
            HeartbeatStoreFactory(
                object : DispatcherProvider {
                    override val main: CoroutineDispatcher = dispatcher
                    override val default: CoroutineDispatcher = dispatcher
                    override val io: CoroutineDispatcher = dispatcher
                },
            ),
            scope.backgroundScope,
        )

        suspend fun subscribe(): BridgeProvider {
            val provider = CompletableDeferred<BridgeProvider>()
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

    private class FakeBridgeMachine(initial: ChromiumBridgeState) :
        Machine<ChromiumBridgeState, ChromiumBridgeIntent, ChromiumBridgeOutput> {
        override val name = "chromium_bridge"
        override val state = MutableStateFlow(initial)
        override val outputs = MutableSharedFlow<ChromiumBridgeOutput>(extraBufferCapacity = 8)
        val sent = mutableListOf<ChromiumBridgeIntent>()

        override suspend fun send(intent: ChromiumBridgeIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
}
