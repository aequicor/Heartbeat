package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserAgentPolicy
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserReferrer
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserReferrerPolicy
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserWorkspaceStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserSessionTest {
    @Test
    fun `linked navigation retains referrer alongside the inherited session and origin`() = runTest {
        val fixture = Fixture(this)
        fixture.create("session-a")
        fixture.guards.first().restrict("http://192.168.1.1/")
        val referrer = BrowserReferrer("http://192.168.1.1/private", BrowserReferrerPolicy.Origin)
        fixture.links.first()(BrowserCommand.Load("http://192.168.1.1/next", referrer))
        runCurrent()
        val controller = RecordingBrowserController()
        fixture.surfaces.last().attached(controller)
        runCurrent()
        assertEquals(
            listOf<BrowserViewCommand>(BrowserViewCommand.Load("http://192.168.1.1/next", referrer)),
            controller.commands,
        )
        assertEquals("session-a", fixture.session.tabs.value.first.last().owner)
        assertTrue(fixture.guards.last().allows("http://192.168.1.1/another", BrowserRequestKind.MainDocument))
        assertFalse(fixture.guards.last().allows("http://10.0.0.1/", BrowserRequestKind.MainDocument))
    }

    @Test
    fun `a replaced control request survives an older transfer waiting for native cancellation`() = runTest {
        for (operation in listOf(BrowserOperation.ApproveControl, BrowserOperation.RejectControl)) {
            val cancelling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = Fixture(this, nativeCommand = {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cancelling.complete(Unit)
                        release.await()
                    }
                }
            })
            val tab = fixture.create()
            fixture.attach()
            runCurrent()
            val read = async { fixture.request(BrowserOperation.Read, tab) }
            runCurrent()
            val first = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
            val transfer = async { fixture.request(operation, tab, controlRequestId = first.requestId) }
            runCurrent()
            assertTrue(cancelling.isCompleted)
            val second = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
            release.complete(Unit)
            assertNotNull(transfer.await().error)
            assertFailsWith<CancellationException> { read.await() }
            val pending = fixture.session.tabs.value.first.single()
            assertEquals(second.requestId, pending.controlRequestId)
            assertEquals("session-a", pending.pendingOwner)
            assertNull(pending.owner)
        }
    }

    @Test
    fun `agent sessions cannot read or close each others tabs and human takeover revokes access`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create("session-a")
        assertNotNull(fixture.request(BrowserOperation.Read, tab, "session-b").error)
        assertNotNull(fixture.request(BrowserOperation.CloseTab, tab, "session-b").error)
        assertEquals(0, fixture.list("session-b").size)
        assertNull(fixture.request(BrowserOperation.TakeControl, tab).error)
        assertNotNull(fixture.request(BrowserOperation.Read, tab, "session-a").error)
        assertNull(fixture.request(BrowserOperation.ReturnControl, tab).error)
        assertNull(fixture.request(BrowserOperation.CloseTab, tab, "session-a").error)
    }

    @Test
    fun `requesting a human tab grants no access until UI approves the requesting session`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create()
        assertNull(fixture.request(BrowserOperation.RequestControl, tab, "session-a").error)
        val pending = fixture.session.tabs.value.first.single()
        assertEquals("session-a", pending.pendingOwner)
        assertNull(pending.owner)
        assertEquals(0, fixture.list("session-a").size)
        assertNotNull(fixture.request(BrowserOperation.Read, tab, "session-a").error)
        assertNotNull(fixture.request(BrowserOperation.CloseTab, tab, "session-a").error)
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, tab, "session-a").error)
        assertNull(
            fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = pending.controlRequestId).error,
        )
        val approved = fixture.session.tabs.value.first.single()
        assertEquals("session-a", approved.owner)
        assertNull(approved.pendingOwner)
        assertNull(approved.controlRequestId)
        assertFalse(approved.isHumanControlled)
        assertEquals(1, fixture.list("session-a").size)
        assertEquals(0, fixture.list("session-b").size)
        assertNotNull(fixture.request(BrowserOperation.CloseTab, tab, "session-b").error)
        assertNull(fixture.request(BrowserOperation.CloseTab, tab, "session-a").error)
    }

    @Test
    fun `another session cannot replace or reject a pending request`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create()
        val request = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
        assertNull(request.error)
        assertNotNull(fixture.request(BrowserOperation.RequestControl, tab, "session-b").error)
        assertNotNull(fixture.request(BrowserOperation.RejectControl, tab, "session-b").error)
        assertEquals("session-a", fixture.session.tabs.value.first.single().pendingOwner)
        assertNull(fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = request.requestId).error)
        assertEquals("session-a", fixture.session.tabs.value.first.single().owner)
    }

    @Test
    fun `rejection keeps the tab human owned and a later approval requires a new request`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create()
        val first = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
        assertNull(fixture.request(BrowserOperation.RejectControl, tab, controlRequestId = first.requestId).error)
        val rejected = fixture.session.tabs.value.first.single()
        assertNull(rejected.owner)
        assertNull(rejected.pendingOwner)
        assertNull(rejected.controlRequestId)
        assertTrue(rejected.isHumanControlled)
        assertNotNull(fixture.request(BrowserOperation.Read, tab, "session-a").error)
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, tab).error)
        val second = fixture.request(BrowserOperation.RequestControl, tab, "session-b")
        assertNull(second.error)
        assertNull(fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = second.requestId).error)
        assertEquals("session-b", fixture.session.tabs.value.first.single().owner)
        assertNotNull(fixture.request(BrowserOperation.CloseTab, tab, "session-a").error)
    }

    @Test
    fun `stale approval and rejection cannot authorize or clear the next requesting session`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create()
        val first = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
        assertNull(fixture.request(BrowserOperation.RejectControl, tab, controlRequestId = first.requestId).error)
        val second = fixture.request(BrowserOperation.RequestControl, tab, "session-b")
        val pending = fixture.session.tabs.value.first.single()
        assertEquals("session-b", pending.pendingOwner)
        assertEquals(second.requestId, pending.controlRequestId)
        for (operation in listOf(BrowserOperation.ApproveControl, BrowserOperation.RejectControl)) {
            assertNotNull(fixture.request(operation, tab, controlRequestId = first.requestId).error)
            assertEquals(pending, fixture.session.tabs.value.first.single())
        }
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, tab).error)
        assertEquals(pending, fixture.session.tabs.value.first.single())
        assertNull(fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = second.requestId).error)
        assertEquals("session-b", fixture.session.tabs.value.first.single().owner)
        assertNotNull(fixture.request(BrowserOperation.Read, tab, "session-a").error)
    }

    @Test
    fun `control requests cannot transfer an existing agent owned tab`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create("session-a")
        assertNotNull(fixture.request(BrowserOperation.RequestControl, tab, "session-b").error)
        assertNotNull(fixture.request(BrowserOperation.RequestControl, tab).error)
        assertEquals("session-a", fixture.session.tabs.value.first.single().owner)
        assertNull(fixture.session.tabs.value.first.single().pendingOwner)
    }

    @Test
    fun `only the user may clear all browsing contexts including agent and pending tabs`() = runTest {
        val fixture = Fixture(this)
        val humanTab = fixture.create()
        fixture.create("session-a")
        fixture.request(BrowserOperation.RequestControl, humanTab, "session-b")
        assertNotNull(fixture.request(BrowserOperation.ClearData, owner = "session-a").error)
        assertEquals(2, fixture.session.tabs.value.first.size)
        assertTrue(fixture.closed.isEmpty())
        assertNull(fixture.request(BrowserOperation.ClearData).error)
        assertEquals(emptyList(), fixture.session.tabs.value.first)
        assertNull(fixture.session.tabs.value.second)
        assertEquals(2, fixture.closed.size)
        assertEquals(1, fixture.clearCount)
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, humanTab).error)
    }

    @Test
    fun `new tabs wait until persistent data clearing finishes`() = runTest {
        val released = CompletableDeferred<Unit>()
        val fixture = Fixture(this, released)
        fixture.create()
        val clearing = async { fixture.request(BrowserOperation.ClearData) }
        runCurrent()
        assertFalse(clearing.isCompleted)
        assertTrue(fixture.session.tabs.value.first.isEmpty())
        assertNotNull(fixture.request(BrowserOperation.NewTab, owner = "session-a").error)
        assertEquals(1, fixture.clearCount)
        released.complete(Unit)
        assertNull(clearing.await().error)
        assertNull(fixture.request(BrowserOperation.NewTab, owner = "session-a").error)
        assertEquals(1, fixture.session.tabs.value.first.size)
    }

    @Test
    fun `page load timeout is reported as an operation error rather than a cancellation`() = runTest {
        val fixture = Fixture(this, nativeCommand = { awaitCancellation() })
        val tab = fixture.create("session-a")
        fixture.attach()
        val result = fixture.session.perform(
            BrowserRequest(
                "navigate",
                BrowserOperation.Navigate,
                tab,
                "session-a",
                buildJsonObject { put("url", "https://example.com/slow") },
            ),
        )
        assertEquals("Page load timed out", result.error)
    }

    @Test
    fun `agent tabs stop unapproved private navigations started by the page and say why`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create("session-a")
        var redirect: String? = null
        fixture.attachNavigation(0) { redirect }
        runCurrent()
        suspend fun open(url: String) = fixture.request(BrowserOperation.Navigate, tab, "session-a", url = url).error

        redirect = "http://192.168.1.1/"
        assertTrue(open("https://example.com/").orEmpty().contains("private-network"))
        redirect = null
        assertNull(open("https://example.com/"))
        // The dispatcher asked the user before this navigation: the page may move within the approved host only.
        redirect = "http://192.168.1.1/login"
        assertNull(open("http://192.168.1.1/admin"))
        redirect = "http://10.0.0.1/"
        assertTrue(open("http://192.168.1.1/admin").orEmpty().contains("private-network"))
        // Opening another address withdraws the approval.
        redirect = null
        assertNull(open("https://example.com/"))
        assertFalse(fixture.guards.single().allows("http://192.168.1.1/", BrowserRequestKind.MainDocument))
        // Numeric shorthands of loopback are not addresses the browser accepts at all.
        assertEquals("Invalid URL", open("2130706433"))
        assertEquals("Invalid URL", open("127.1"))
    }

    @Test
    fun `only agent controlled tabs are restricted and a handed over page stays approved`() = runTest {
        val fixture = Fixture(this)
        val user = fixture.create()
        val userGuard = fixture.guards.single()
        fixture.attachNavigation(0) { null }
        runCurrent()
        assertNull(fixture.request(BrowserOperation.Navigate, user, url = "http://192.168.1.1/").error)
        assertFalse(userGuard.isRestricted)

        val agent = fixture.create("session-a")
        val agentGuard = fixture.guards.last()
        assertTrue(agentGuard.isRestricted)
        fixture.attachNavigation(1) { null }
        runCurrent()
        // Only the owner's own (dispatcher-approved) navigation approves an address for the agent's tab.
        val foreign = fixture.request(BrowserOperation.Navigate, agent, url = "http://192.168.1.1/").error
        assertTrue(foreign.orEmpty().contains("private-network"))
        assertNull(fixture.request(BrowserOperation.TakeControl, agent).error)
        assertFalse(agentGuard.isRestricted)
        assertNull(fixture.request(BrowserOperation.ReturnControl, agent).error)
        assertTrue(agentGuard.isRestricted)

        val request = fixture.request(BrowserOperation.RequestControl, user, "session-b")
        assertNull(fixture.request(BrowserOperation.ApproveControl, user, controlRequestId = request.requestId).error)
        assertTrue(userGuard.isRestricted)
        assertTrue(userGuard.allows("http://192.168.1.1/settings", BrowserRequestKind.MainDocument))
        assertFalse(userGuard.allows("http://10.0.0.1/", BrowserRequestKind.MainDocument))
    }

    @Test
    fun `agent tabs block private iframes of a loaded page and say so separately`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create("session-a")
        fixture.attachNavigation(0, embedded = listOf("http://192.168.1.1/", "https://10.0.0.1/frame")) { null }
        runCurrent()
        val result = fixture.request(BrowserOperation.Navigate, tab, "session-a", url = "https://example.com/")
        assertNull(result.error)
        assertTrue(result.data["blockedContent"]?.jsonPrimitive?.content.orEmpty().contains("private-network"))
        assertNull(result.data["blockedNavigation"])
        val again = fixture.request(BrowserOperation.Navigate, tab, "session-a", url = "https://example.com/")
        assertTrue(again.data.containsKey("blockedContent"))

        val user = fixture.create()
        fixture.attachNavigation(1, embedded = listOf("http://192.168.1.1/")) { null }
        runCurrent()
        val own = fixture.request(BrowserOperation.Navigate, user, url = "https://example.com/")
        assertNull(own.error)
        assertFalse(own.data.containsKey("blockedContent"))
    }

    @Test
    fun `a handed over tab approves the origin of the page it shows now`() = runTest {
        val fixture = Fixture(this)
        val user = fixture.create()
        val guard = fixture.guards.single()
        fixture.attachNavigation(0) { url ->
            "http://192.168.1.1:8080/".takeIf { url.startsWith("http://192.168.1.1/") }
        }
        runCurrent()
        assertNull(fixture.request(BrowserOperation.Navigate, user, url = "http://192.168.1.1/").error)
        val request = fixture.request(BrowserOperation.RequestControl, user, "session-b")
        assertNull(fixture.request(BrowserOperation.ApproveControl, user, controlRequestId = request.requestId).error)
        assertTrue(guard.allows("http://192.168.1.1:8080/next", BrowserRequestKind.MainDocument))
        assertFalse(guard.allows("http://192.168.1.1/", BrowserRequestKind.MainDocument))
    }

    @Test
    fun `session groups stay contiguous and linked tabs inherit ownership next to their parent`() = runTest {
        val fixture = Fixture(this)
        val first = fixture.create("session-a")
        val other = fixture.create("session-b")
        val second = fixture.create("session-a")
        assertEquals(listOf(first, second, other), fixture.session.tabs.value.first.map { it.id })
        fixture.links.first()(BrowserCommand.Load("https://example.com/linked"))
        runCurrent()
        val linked = fixture.session.tabs.value.first[1]
        assertEquals("session-a", linked.owner)
        assertEquals(listOf(first, linked.id, second, other), fixture.session.tabs.value.first.map { it.id })
        assertEquals(linked.id, fixture.session.tabs.value.second)
        assertEquals(3, fixture.list("session-a").size)
        assertEquals(1, fixture.list("session-b").size)
        assertNotNull(fixture.request(BrowserOperation.CloseTab, linked.id, "session-b").error)
        assertNull(fixture.request(BrowserOperation.CloseTab, linked.id, "session-a").error)
        assertEquals(second, fixture.session.tabs.value.second)
    }

    @Test
    fun `user new tab inherits selected group while a foreign agent cannot join it`() = runTest {
        val fixture = Fixture(this)
        val parent = fixture.create("session-a")
        assertNull(fixture.request(BrowserOperation.NewTab, parent).error)
        assertEquals(listOf("session-a", "session-a"), fixture.session.tabs.value.first.map { it.owner })
        assertNotNull(fixture.request(BrowserOperation.NewTab, parent, "session-b").error)
        assertEquals(2, fixture.session.tabs.value.first.size)
    }

    @Test
    fun `linked tab preserves approved origin and cannot escape via another private address`() = runTest {
        val fixture = Fixture(this)
        val parent = fixture.create("session-a")
        fixture.guards.first().restrict("http://192.168.1.1/")
        fixture.links.first()(BrowserCommand.Load("http://192.168.1.1/login"))
        runCurrent()
        val guard = fixture.guards.last()
        assertTrue(guard.isRestricted)
        assertTrue(guard.allows("http://192.168.1.1/next", BrowserRequestKind.MainDocument))
        assertFalse(guard.allows("http://10.0.0.1/", BrowserRequestKind.MainDocument))
        fixture.links.first()(BrowserCommand.Load("http://10.0.0.1/"))
        runCurrent()
        assertEquals(2, fixture.session.tabs.value.first.size)
        fixture.request(BrowserOperation.CloseTab, parent, "session-a")
        fixture.links.first()(BrowserCommand.Load("https://example.com/stale"))
        runCurrent()
        assertEquals(1, fixture.session.tabs.value.first.size)
    }

    @Test
    fun `personal links and human controlled siblings keep their group and control state`() = runTest {
        val fixture = Fixture(this)
        fixture.create()
        fixture.links.first()(BrowserCommand.Load("https://example.com/"))
        runCurrent()
        assertTrue(fixture.session.tabs.value.first.all { it.owner == null })
        val agent = fixture.create("session-a")
        fixture.request(BrowserOperation.TakeControl, agent)
        fixture.links.last()(BrowserCommand.Load("http://localhost:8080/"))
        runCurrent()
        val child = fixture.session.tabs.value.first.last()
        assertEquals("session-a", child.owner)
        assertTrue(child.isHumanControlled)
        assertFalse(fixture.guards.last().isRestricted)
    }

    internal class Fixture(
        scope: TestScope,
        private val clearRelease: CompletableDeferred<Unit>? = null,
        private val nativeCommand: (suspend () -> String)? = null,
        val storage: BrowserWorkspaceStorage = MemoryBrowserWorkspaceStorage(),
        private val failCreateAt: Int? = null,
    ) {
        val closed = mutableListOf<BrowserSurface>()
        val surfaces = mutableListOf<BrowserSurface>()
        val guards = mutableListOf<DesktopBrowserAgentGuard>()
        val links = mutableListOf<(BrowserCommand.Load) -> Unit>()
        private val panels = mutableListOf<DesktopBrowserPanel>()
        var clearCount = 0
        private var requestIndex = 0
        private val policy = object : BrowserAgentPolicy {
            private val enabled = MutableStateFlow(true)

            override fun observe() = enabled

            override suspend fun isEnabled() = enabled.value
        }
        private val dispatchers = BrowserTestDispatchers(StandardTestDispatcher(scope.testScheduler))
        private val resources = object : DesktopBrowserPanelFactory {
            override suspend fun clearData() {
                clearCount++
                clearRelease?.await()
            }

            override fun close(surface: BrowserSurface) {
                closed += surface
            }

            override fun create(
                surface: BrowserSurface,
                guard: DesktopBrowserAgentGuard,
                openTab: ((BrowserCommand.Load) -> Unit)?,
            ): DesktopBrowserPanel {
                check(surfaces.size != failCreateAt) { "Panel creation failed" }
                links += requireNotNull(openTab)
                surfaces += surface
                guards += guard
                return panel(surface)
            }

            private fun panel(surface: BrowserSurface) =
                DesktopBrowserPanel(surface, scope.backgroundScope, dispatchers) { _, _ ->
                    val command = checkNotNull(nativeCommand) { "Native runtime must not be created for empty tabs" }
                    object : DesktopBrowserRuntime {
                        override val component = JPanel()
                        override fun start(initialUrl: String) = Unit
                        override fun release() = Unit
                        override fun execute(command: BrowserViewCommand) = Unit
                        override suspend fun devTools(method: String, arguments: JsonObject): String = command()
                    }
                }.also(panels::add)
        }
        val session = DesktopBrowserSession(
            BrowserTestScope(scope.backgroundScope),
            dispatchers,
            resources,
            policy,
            storage,
        )

        fun attach() = SwingUtilities.invokeAndWait { panels.single().createComponent() }

        suspend fun create(owner: String? = null): String {
            session.setEnabled(true)
            return request(BrowserOperation.NewTab, owner = owner).data.getValue("tabId").jsonPrimitive.content
        }

        suspend fun request(
            operation: BrowserOperation,
            tab: String? = null,
            owner: String? = null,
            controlRequestId: String? = null,
            url: String? = null,
        ): BrowserResult = session.perform(
            BrowserRequest(
                "request-${requestIndex++}",
                operation,
                tab,
                owner,
                buildJsonObject {
                    controlRequestId?.let { put("controlRequestId", it) }
                    url?.let { put("url", it) }
                },
            ),
        )

        /**
         * Attaches a native view that follows each load through [redirect] and passes every main-document hop
         * through the tab's real policy, as the engine does.
         */
        fun attachNavigation(index: Int, embedded: List<String> = emptyList(), redirect: (String) -> String?) {
            val surface = surfaces[index]
            val policy = DesktopBrowserPolicy({ false }, { false }, {}, {}, {}, guards[index])
            val controller = object : BrowserViewController {
                override fun execute(command: BrowserViewCommand) {
                    if (command !is BrowserViewCommand.Load) return
                    val hops = listOfNotNull(command.url, redirect(command.url))
                    val isAllowed = hops.all { policy.allows(it, BrowserRequestKind.MainDocument) }
                    // The loaded page's iframes pass the same policy; a refused one leaves the page loaded.
                    if (isAllowed) embedded.forEach { policy.allows(it, BrowserRequestKind.ChildDocument) }
                    val state = if (isAllowed) {
                        BrowserViewState(url = hops.last())
                    } else {
                        BrowserViewState(url = command.url, error = BrowserViewError.UnsupportedAddress)
                    }
                    surface.changed(this, state)
                }
            }
            surface.attached(controller)
        }

        suspend fun list(owner: String) =
            request(BrowserOperation.ListTabs, owner = owner).data.getValue("tabs").jsonArray
    }
}
