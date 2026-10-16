package io.aequicor.heartbeat.feature.browser.impl.presentation.store

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDisplay
import io.aequicor.heartbeat.feature.browser.api.BrowserError
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.browser.api.BrowserTab
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserFaviconImage
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserShortcut
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserModelTest {
    @Test
    fun `new tab passes selected parent while groups reflect session ownership`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.machine.state.value = ready(BrowserPage()).copy(
            tabs = listOf(BrowserTab("parent", owner = "session-a")),
            selectedTabId = "parent",
        )
        runCurrent()
        assertEquals("session-a", screen.states.value.tabs.single().groupId)
        fixture.model.store.intent(BrowserScreenIntent.NewTab)
        runCurrent()
        assertEquals(
            BrowserRequest("", BrowserOperation.NewTab, "parent"),
            fixture.machine.performed().single().copy(id = ""),
        )
    }

    @Test
    fun `each tab reflects its own page loading`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.machine.state.value = ready(BrowserPage()).copy(
            tabs = listOf(
                BrowserTab("loading", BrowserPage("https://example.com/", isLoading = true)),
                BrowserTab("idle", BrowserPage("https://example.org/", "Idle", isAwaitingResponse = true)),
                BrowserTab("waiting", BrowserPage("https://example.net/", isLoading = true, isAwaitingResponse = true)),
            ),
            selectedTabId = "idle",
        )
        runCurrent()
        assertEquals(listOf(true, false, true), screen.states.value.tabs.map { it.isLoading })
        assertEquals(listOf(false, false, true), screen.states.value.tabs.map { it.isAwaitingResponse })
        assertFalse(screen.states.value.isLoading)
    }

    @Test
    fun `tabs carry their site icon address and decoded icons are reflected`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.machine.state.value = ready(BrowserPage()).copy(
            tabs = listOf(
                BrowserTab("tab", BrowserPage("https://example.com/", faviconUrl = "https://example.com/i.png")),
            ),
            selectedTabId = "tab",
        )
        val icon = BrowserFaviconImage(byteArrayOf(1))
        fixture.favicons.value = mapOf("https://example.com/i.png" to icon)
        runCurrent()
        val state = screen.states.value
        assertEquals(icon, state.favicons[state.tabs.single().faviconUrl])
    }

    @Test
    fun `tab shortcuts cycle the visible group and pick positions`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.machine.state.value = ready(BrowserPage()).copy(
            tabs = listOf(
                BrowserTab("a"),
                BrowserTab("b"),
                BrowserTab("c"),
                BrowserTab("agent", owner = "session"),
            ),
            selectedTabId = "c",
        )
        runCurrent()
        assertEquals(3, screen.states.value.visibleTabs.size)
        listOf(
            BrowserShortcut.NextTab,
            BrowserShortcut.PreviousTab,
            BrowserShortcut.SelectTab(2),
            BrowserShortcut.SelectTab(null),
            BrowserShortcut.SelectTab(7),
        ).forEach { fixture.model.store.intent(BrowserScreenIntent.Shortcut(it)) }
        runCurrent()
        assertEquals(
            listOf("a", "b", "b", "c"),
            fixture.machine.sent.filterIsInstance<BrowserIntent.Public.SelectTab>().map { it.id },
        )
    }

    @Test
    fun `tab keys from inside a page act like keys pressed in the strip`() = runTest {
        val fixture = Fixture(this)
        fixture.subscribe()
        fixture.machine.state.value = ready(BrowserPage()).copy(
            tabs = listOf(BrowserTab("a"), BrowserTab("b")),
            selectedTabId = "a",
        )
        runCurrent()
        fixture.shortcuts.emit(BrowserShortcut.NextTab)
        runCurrent()
        assertEquals(BrowserIntent.Public.SelectTab("b"), fixture.machine.sent.last())
    }

    @Test
    fun `a closed tab reopens at its address next to the selected tab`() = runTest {
        val fixture = Fixture(this)
        fixture.subscribe()
        fixture.machine.state.value = ready(BrowserPage()).copy(
            tabs = listOf(BrowserTab("a", BrowserPage("https://example.com/a")), BrowserTab("b")),
            selectedTabId = "a",
        )
        runCurrent()
        fixture.model.store.intent(BrowserScreenIntent.Shortcut(BrowserShortcut.CloseTab))
        fixture.model.store.intent(BrowserScreenIntent.CloseTab("b"))
        fixture.model.store.intent(BrowserScreenIntent.Shortcut(BrowserShortcut.ReopenClosedTab))
        fixture.model.store.intent(BrowserScreenIntent.Shortcut(BrowserShortcut.ReopenClosedTab))
        runCurrent()
        val performed = fixture.machine.performed().map { it.copy(id = "") }
        assertEquals(
            listOf(
                BrowserRequest("", BrowserOperation.CloseTab, "a"),
                BrowserRequest("", BrowserOperation.CloseTab, "b"),
                BrowserRequest(
                    "",
                    BrowserOperation.NewTab,
                    "a",
                    arguments = JsonObject(mapOf("url" to JsonPrimitive("https://example.com/a"))),
                ),
            ),
            performed,
        )
    }

    @Test
    fun `address submit normalizes host before machine validation`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.model.store.intent(BrowserScreenIntent.AddressChanged("localhost:8080"))
        runCurrent()
        assertEquals("localhost:8080", screen.states.value.address)
        fixture.model.store.intent(BrowserScreenIntent.Open)
        runCurrent()
        assertEquals(
            BrowserIntent.Public.Open("https://localhost:8080"),
            fixture.machine.sent.last(),
        )
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
        for (intent in listOf(
            BrowserScreenIntent.Back,
            BrowserScreenIntent.Forward,
            BrowserScreenIntent.Reload,
        )) {
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

    @Test
    fun `tab actions become correlated machine operations`() = runTest {
        val fixture = Fixture(this)
        fixture.subscribe()
        fixture.model.store.intent(BrowserScreenIntent.NewTab)
        fixture.model.store.intent(BrowserScreenIntent.CloseTab("tab"))
        fixture.model.store.intent(BrowserScreenIntent.ClearData)
        fixture.model.store.intent(BrowserScreenIntent.ApproveControl("tab", "control"))
        fixture.model.store.intent(BrowserScreenIntent.RejectControl("tab", "control"))
        runCurrent()
        val requests = fixture.machine.performed()
        val control = JsonObject(mapOf("controlRequestId" to JsonPrimitive("control")))
        assertEquals(
            listOf(
                BrowserRequest("", BrowserOperation.NewTab),
                BrowserRequest("", BrowserOperation.CloseTab, "tab"),
                BrowserRequest("", BrowserOperation.ClearData),
                BrowserRequest("", BrowserOperation.ApproveControl, "tab", arguments = control),
                BrowserRequest("", BrowserOperation.RejectControl, "tab", arguments = control),
            ),
            requests.map { it.copy(id = "") },
        )
        assertEquals(requests.size, requests.map { it.id }.toSet().size)
        assertTrue(requests.all { it.id.isNotBlank() })
    }

    @Test
    fun `control toggle follows the selected tab ownership`() = runTest {
        val fixture = Fixture(this)
        fixture.subscribe()
        fixture.model.store.intent(BrowserScreenIntent.ToggleControl)
        runCurrent()
        assertTrue(fixture.machine.performed().isEmpty())
        fixture.machine.state.value = ready(BrowserPage()).copy(
            tabs = listOf(BrowserTab("agent"), BrowserTab("human", isHumanControlled = true)),
            selectedTabId = "agent",
        )
        runCurrent()
        fixture.model.store.intent(BrowserScreenIntent.ToggleControl)
        runCurrent()
        val running = fixture.machine.state.value as BrowserState.Running
        fixture.machine.state.value = running.copy(selectedTabId = "human")
        runCurrent()
        fixture.model.store.intent(BrowserScreenIntent.ToggleControl)
        runCurrent()
        assertEquals(
            listOf(
                BrowserRequest("", BrowserOperation.TakeControl, "agent"),
                BrowserRequest("", BrowserOperation.ReturnControl, "human"),
            ),
            fixture.machine.performed().map { it.copy(id = "") },
        )
    }

    @Test
    fun `expansion toggles between panel and expanded display`() = runTest {
        val fixture = Fixture(this)
        fixture.subscribe()
        fixture.model.store.intent(BrowserScreenIntent.ToggleExpanded)
        runCurrent()
        fixture.machine.state.value = ready(BrowserPage()).copy(display = BrowserDisplay.Expanded)
        runCurrent()
        fixture.model.store.intent(BrowserScreenIntent.ToggleExpanded)
        fixture.model.store.intent(BrowserScreenIntent.Hide)
        runCurrent()
        assertEquals(
            listOf(
                BrowserIntent.Public.Display(BrowserDisplay.Expanded),
                BrowserIntent.Public.Display(BrowserDisplay.Panel),
                BrowserIntent.Public.Display(BrowserDisplay.Hidden),
            ),
            fixture.machine.sent.filterIsInstance<BrowserIntent.Public.Display>(),
        )
    }

    @Test
    fun `error details reset on another failure and certificate action keeps original snapshot`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        val failure = io.aequicor.heartbeat.feature.browser.api.BrowserLoadFailure(
            "https://example.com/",
            "ERR_CERT_DATE_INVALID",
            true,
            true,
            "first",
        )
        val state = ready(BrowserPage(url = failure.url, failure = failure)).copy(
            areErrorPagesEnabled = true,
            selectedTabId = "tab",
            tabs = listOf(BrowserTab("tab")),
        )
        fixture.machine.state.value = state
        runCurrent()
        val original = requireNotNull(screen.states.value.failure)
        fixture.model.store.intent(BrowserScreenIntent.ToggleErrorDetails)
        runCurrent()
        assertTrue(screen.states.value.isErrorDetailsExpanded)
        fixture.machine.state.value = state.copy(page = state.page.copy(failure = failure.copy(id = "next")))
        runCurrent()
        assertFalse(screen.states.value.isErrorDetailsExpanded)
        fixture.model.store.intent(BrowserScreenIntent.ProceedCertificate("tab", original))
        runCurrent()
        assertEquals(BrowserIntent.Internal.ProceedCertificate("tab", failure), fixture.machine.sent.last())
        fixture.machine.state.value = state.copy(areErrorPagesEnabled = false)
        runCurrent()
        assertEquals(null, screen.states.value.failure)
    }

    private class Fixture(private val scope: TestScope) {
        val machine = TestBrowserMachine()
        val favicons = MutableStateFlow<Map<String, BrowserFaviconImage>>(emptyMap())
        val shortcuts = MutableSharedFlow<BrowserShortcut>()
        private val owner = BrowserTestScope(scope.backgroundScope)
        private val dispatchers = BrowserTestDispatchers(StandardTestDispatcher(scope.testScheduler))
        val model = BrowserModel(
            machine,
            owner,
            HeartbeatStoreFactory(dispatchers),
            object : BrowserSurface by BrowserSurfaceAdapter(owner, dispatchers) {
                override val favicons = this@Fixture.favicons
                override val shortcuts = this@Fixture.shortcuts
            },
        )

        suspend fun subscribe(): Provider<BrowserScreenState, BrowserScreenIntent, BrowserScreenAction> {
            val provider = CompletableDeferred<
                Provider<BrowserScreenState, BrowserScreenIntent, BrowserScreenAction>,
            >()
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

    fun performed(): List<BrowserRequest> = sent.filterIsInstance<BrowserIntent.Public.Perform>().map { it.request }
}
