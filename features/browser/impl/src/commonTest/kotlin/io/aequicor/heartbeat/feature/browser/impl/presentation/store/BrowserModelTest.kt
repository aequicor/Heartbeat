package io.aequicor.heartbeat.feature.browser.impl.presentation.store

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.browser.api.BrowserError
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurfaceAdapter
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestDispatchers
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestScope
import kotlinx.coroutines.CompletableDeferred
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserModelTest {
    @Test
    fun `address submit forwards raw input for machine validation`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.model.store.intent(BrowserScreenIntent.AddressChanged("localhost:8080"))
        runCurrent()
        assertEquals("localhost:8080", screen.states.value.address)
        fixture.model.store.intent(BrowserScreenIntent.Open)
        runCurrent()
        assertEquals(BrowserIntent.Public.Open("localhost:8080"), fixture.machine.sent.last())
        assertFalse(screen.states.value.isAddressEdited)
    }

    @Test
    fun `native redirects update the address but preserve an unfinished edit`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.machine.state.value = ready(BrowserPage("https://example.com/redirect", "Page", isBackAvailable = true))
        runCurrent()
        assertEquals("https://example.com/redirect", screen.states.value.address)
        assertTrue(screen.states.value.isBackAvailable)
        fixture.model.store.intent(BrowserScreenIntent.AddressChanged("another.example"))
        runCurrent()
        fixture.machine.state.value = ready(BrowserPage("https://example.com/redirect", "Updated title"))
        runCurrent()
        assertEquals("another.example", screen.states.value.address)
        assertEquals("Updated title", screen.states.value.title)
    }

    @Test
    fun `explicit history navigation and reload discard the address draft`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        for (intent in listOf(BrowserScreenIntent.Back, BrowserScreenIntent.Forward, BrowserScreenIntent.Reload)) {
            fixture.machine.state.value = ready(BrowserPage("https://example.com/current"))
            runCurrent()
            fixture.model.store.intent(BrowserScreenIntent.AddressChanged("unfinished.example"))
            runCurrent()
            fixture.model.store.intent(intent)
            runCurrent()
            assertFalse(screen.states.value.isAddressEdited)
            assertEquals("https://example.com/current", screen.states.value.address)
            fixture.machine.state.value = ready(BrowserPage("https://example.com/destination"))
            runCurrent()
            assertEquals("https://example.com/destination", screen.states.value.address)
        }
    }

    @Test
    fun `disabled state and errors are reflected without domain types in UI`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.machine.state.value = ready(BrowserPage("https://example.com", error = BrowserError.LoadFailed))
        runCurrent()
        assertEquals(BrowserScreenError.LoadFailed, screen.states.value.error)
        fixture.machine.state.value = BrowserState.Running(isConfigured = true)
        runCurrent()
        assertEquals(BrowserPhase.Disabled, screen.states.value.phase)
        assertFalse(screen.states.value.isLoading)
    }

    private class Fixture(private val scope: TestScope) {
        val machine = TestBrowserMachine()
        private val owner = BrowserTestScope(scope.backgroundScope)
        private val dispatchers = BrowserTestDispatchers(StandardTestDispatcher(scope.testScheduler))
        val model = BrowserModel(
            machine,
            owner,
            HeartbeatStoreFactory(dispatchers),
            BrowserSurfaceAdapter(owner, dispatchers),
        )

        suspend fun subscribe(): Provider<BrowserScreenState, BrowserScreenIntent, BrowserScreenAction> {
            val provider = CompletableDeferred<Provider<BrowserScreenState, BrowserScreenIntent, BrowserScreenAction>>()
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

private fun ready(page: BrowserPage) = BrowserState.Running(isConfigured = true, isEnabled = true, page = page)

private class TestBrowserMachine : Machine<BrowserState, BrowserIntent, BrowserOutput> {
    override val name = "test/browser"
    override val state = MutableStateFlow<BrowserState>(ready(BrowserPage()))
    override val outputs = MutableSharedFlow<BrowserOutput>()
    val sent = mutableListOf<BrowserIntent>()

    override suspend fun send(intent: BrowserIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}
