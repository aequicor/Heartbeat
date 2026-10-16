package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserLoadFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserAgentPolicy
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserFaviconPolicy
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
import kotlinx.coroutines.flow.first
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
    fun `profile restores conservative certificate labels before opening tabs`() = runTest {
        val storage = MemoryBrowserWorkspaceStorage()
        val first = Fixture(this, storage = storage)
        first.create()
        first.exceptions.remember("https://example.com:8443/")
        first.create()
        val restored = Fixture(this, storage = storage)
        restored.session.setEnabled(true)
        assertTrue(restored.exceptions.contains("https://example.com/"))
        assertFalse(restored.exceptions.contains("https://other.example/"))
    }

    @Test
    fun `network recovery reloads only idle human network failures while enabled`() = runTest {
        val fixture = Fixture(this)
        val cases = listOf(
            Triple(null, "ERR_NAME_NOT_RESOLVED", false),
            Triple("agent", "ERR_CONNECTION_RESET", false),
            Triple(null, "ERR_CERT_AUTHORITY_INVALID", false),
            Triple(null, "ERR_TOO_MANY_REDIRECTS", false),
            Triple(null, "ERR_INTERNET_DISCONNECTED", true),
        )
        val controllers = cases.map { (owner, code, loading) ->
            fixture.create(owner)
            val controller = RecordingBrowserController()
            val surface = fixture.surfaces.last()
            surface.attached(controller)
            surface.changed(
                controller,
                BrowserViewState(
                    url = "https://example.com/",
                    isLoading = loading,
                    failure = BrowserLoadFailure(
                        "https://example.com/",
                        code,
                        isCertificate = code.startsWith("ERR_CERT_"),
                    ),
                ),
            )
            controller
        }
        runCurrent()
        fixture.errorPages.value = false
        fixture.session.retryNetworkErrors()
        assertTrue(controllers.all { it.commands.isEmpty() })
        fixture.errorPages.value = true
        fixture.session.retryNetworkErrors()
        runCurrent()
        assertEquals(listOf<BrowserViewCommand>(BrowserViewCommand.Reload), controllers.first().commands)
        assertTrue(controllers.drop(1).all { it.commands.isEmpty() })
    }

    @Test
    fun `a human certificate warning cannot be handed to an agent`() = runTest {
        val fixture = Fixture(this, nativeCommand = { error("Unexpected native access") })
        val tab = fixture.create()
        fixture.attach()
        runCurrent()
        fixture.emit(
            BrowserViewState(
                url = "https://example.com/",
                failure = BrowserLoadFailure(
                    "https://example.com/",
                    "ERR_CERT_AUTHORITY_INVALID",
                    isCertificate = true,
                ),
            ),
        )
        runCurrent()
        val pending = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = pending.requestId).error)
        for (operation in listOf(BrowserOperation.Read, BrowserOperation.Click, BrowserOperation.Key)) {
            assertNotNull(fixture.request(operation, tab, "session-a").error)
        }
        assertTrue(fixture.session.tabs.value.first.single().isHumanControlled)
        fixture.emit(BrowserViewState(url = "https://example.com/", isCertificateWarningActive = true))
        runCurrent()
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = pending.requestId).error)
        assertNotNull(fixture.request(BrowserOperation.Key, tab, "session-a").error)
    }

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
        fixture.exceptions.remember("https://example.com/")
        val clearing = async { fixture.request(BrowserOperation.ClearData) }
        runCurrent()
        assertFalse(clearing.isCompleted)
        assertEquals(listOf("https://example.com/"), fixture.storage.load().certificateExceptionUrls)
        assertTrue(fixture.session.tabs.value.first.isEmpty())
        assertNotNull(fixture.request(BrowserOperation.NewTab, owner = "session-a").error)
        assertEquals(1, fixture.clearCount)
        released.complete(Unit)
        assertNull(clearing.await().error)
        assertTrue(fixture.storage.load().certificateExceptionUrls.isEmpty())
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

    @Test
    fun `a loaded page shows its declared icon until the tab leaves its origin`() = runTest {
        val methods = mutableListOf<String>()
        val fixture = Fixture(this, devTools = { method, _ ->
            methods += method
            iconResponse(method, "https://cdn.example.net/icon.svg", type = "image/svg+xml")
        })
        fixture.create()
        fixture.attach()
        runCurrent()
        fixture.emit(BrowserViewState(url = "https://example.com/a"))
        runCurrent()
        assertEquals(listOf("Page.getFrameTree", "Page.createIsolatedWorld", "Runtime.evaluate"), methods)
        assertEquals("https://cdn.example.net/icon.svg", fixture.session.tabs.value.first.single().page.faviconUrl)
        val storage = fixture.storage as MemoryBrowserWorkspaceStorage
        assertEquals("https://cdn.example.net/icon.svg", storage.workspace.tabs.single().faviconUrl)
        fixture.emit(BrowserViewState(url = "https://example.com/b", isLoading = true))
        runCurrent()
        assertEquals("https://cdn.example.net/icon.svg", fixture.session.tabs.value.first.single().page.faviconUrl)
        fixture.emit(BrowserViewState(url = "https://other.example.org/", isLoading = true))
        runCurrent()
        assertEquals("", fixture.session.tabs.value.first.single().page.faviconUrl)
    }

    @Test
    fun `a found icon is fetched once through its tab and clearing browser data forgets it`() = runTest {
        val fetched = mutableListOf<String>()
        val fixture = Fixture(
            this,
            devTools = { method, _ -> iconResponse(method, "https://example.com/icon.png") },
            icons = { url -> fetched.add(url).let { testPng(48, 48) } },
        )
        val tab = fixture.create()
        fixture.attach()
        runCurrent()
        repeat(2) {
            fixture.emit(BrowserViewState(url = "https://example.com/", isLoading = true))
            fixture.emit(BrowserViewState(url = "https://example.com/"))
            runCurrent()
        }
        assertEquals(listOf("https://example.com/icon.png"), fetched)
        assertTrue(fixture.favicons.images.value.getValue("https://example.com/icon.png").png.isNotEmpty())
        fixture.request(BrowserOperation.ClearData)
        assertTrue(fixture.favicons.images.value.isEmpty())
        assertTrue(tab.isNotEmpty())
    }

    @Test
    fun `tab keys pressed inside a page reach the strip`() = runTest {
        val fixture = Fixture(this)
        fixture.create()
        val received = async { fixture.session.shortcuts.first() }
        runCurrent()
        fixture.shortcuts.single()(BrowserShortcut.NextTab)
        assertEquals(BrowserShortcut.NextTab, received.await())
    }

    @Test
    fun `an unreadable page falls back to the origin icon`() = runTest {
        val fixture = Fixture(this, devTools = { _, _ -> error("Page went away") })
        fixture.create()
        fixture.attach()
        runCurrent()
        fixture.emit(BrowserViewState(url = "https://example.com/path?q=1"))
        runCurrent()
        assertEquals("https://example.com/favicon.ico", fixture.session.tabs.value.first.single().page.faviconUrl)
    }

    @Test
    fun `an agent tab refuses a private network icon and disabled icons are never read`() = runTest {
        val agent = Fixture(this, devTools = { method, _ ->
            iconResponse(method, "http://192.168.1.1/icon.png")
        })
        agent.create("session-a")
        agent.attach()
        runCurrent()
        agent.emit(BrowserViewState(url = "https://example.com/"))
        runCurrent()
        assertEquals("", agent.session.tabs.value.first.single().page.faviconUrl)

        var calls = 0
        val disabled = Fixture(this, devTools = { _, _ -> "{}".also { calls++ } }, areFaviconsEnabled = false)
        disabled.create()
        disabled.attach()
        runCurrent()
        disabled.emit(BrowserViewState(url = "https://example.com/"))
        runCurrent()
        assertEquals(0, calls)
        assertEquals("", disabled.session.tabs.value.first.single().page.faviconUrl)
    }

    internal class Fixture(
        scope: TestScope,
        private val clearRelease: CompletableDeferred<Unit>? = null,
        private val nativeCommand: (suspend () -> String)? = null,
        val storage: BrowserWorkspaceStorage = MemoryBrowserWorkspaceStorage(),
        private val failCreateAt: Int? = null,
        private val devTools: (suspend (String, JsonObject) -> String)? = null,
        private val areFaviconsEnabled: Boolean = true,
        private val icons: suspend (String) -> ByteArray = { error("No site icon") },
    ) {
        val errorPages = MutableStateFlow(true)
        val exceptions = DesktopCertificateExceptions()
        private val nativeStates = mutableListOf<(BrowserViewState) -> Unit>()
        val closed = mutableListOf<BrowserSurface>()
        val surfaces = mutableListOf<BrowserSurface>()
        val guards = mutableListOf<DesktopBrowserAgentGuard>()
        val links = mutableListOf<(BrowserCommand.Load) -> Unit>()
        val shortcuts = mutableListOf<(BrowserShortcut) -> Unit>()
        private val panels = mutableListOf<DesktopBrowserPanel>()
        var clearCount = 0
        private var requestIndex = 0
        private val policy = object : BrowserAgentPolicy {
            private val enabled = MutableStateFlow(true)

            override fun observe() = enabled

            override suspend fun isEnabled() = enabled.value
        }
        private val dispatchers = BrowserTestDispatchers(StandardTestDispatcher(scope.testScheduler))
        val favicons = DesktopBrowserFaviconImages(
            dispatchers,
            object : BrowserFaviconPolicy {
                override suspend fun isEnabled() = areFaviconsEnabled
            },
        )
        private val resources = object : DesktopBrowserPanelFactory {
            override val errorPagesEnabled = errorPages
            override fun rememberCertificateException(url: String) = exceptions.remember(url)
            override fun hasCertificateException(url: String): Boolean = exceptions.contains(url)
            override fun certificateExceptionUrls(): List<String> = exceptions.urls()
            override suspend fun clearData() {
                clearCount++
                clearRelease?.await()
                exceptions.clear()
            }

            override fun close(surface: BrowserSurface) {
                closed += surface
            }

            override fun create(
                surface: BrowserSurface,
                guard: DesktopBrowserAgentGuard,
                openTab: ((BrowserCommand.Load) -> Unit)?,
                onShortcut: ((BrowserShortcut) -> Unit)?,
            ): DesktopBrowserPanel {
                shortcuts += requireNotNull(onShortcut)
                check(surfaces.size != failCreateAt) { "Panel creation failed" }
                links += requireNotNull(openTab)
                surfaces += surface
                guards += guard
                return panel(surface)
            }

            private fun panel(surface: BrowserSurface) =
                DesktopBrowserPanel(surface, scope.backgroundScope, dispatchers) { _, changed ->
                    val hasNative = nativeCommand != null || devTools != null
                    check(hasNative) { "Native runtime must not be created for empty tabs" }
                    nativeStates += changed
                    object : DesktopBrowserRuntime {
                        override val component = JPanel()
                        override fun start(initialUrl: String) = Unit
                        override fun release() = Unit
                        override fun execute(command: BrowserViewCommand) = Unit
                        override suspend fun devTools(method: String, arguments: JsonObject): String =
                            devTools?.invoke(method, arguments) ?: nativeCommand!!()

                        override suspend fun loadIcon(url: String): ByteArray = icons(url)
                    }
                }.also(panels::add)
        }
        val session = DesktopBrowserSession(
            BrowserTestScope(scope.backgroundScope),
            dispatchers,
            resources,
            policy,
            storage,
            favicons,
        )

        fun attach() = SwingUtilities.invokeAndWait { panels.single().createComponent() }

        /** Reports [state] from the attached native view and waits until the panel has received it. */
        fun emit(state: BrowserViewState) {
            nativeStates.last()(state)
            SwingUtilities.invokeAndWait {}
        }

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

private fun iconResponse(method: String, url: String, type: String = ""): String = when (method) {
    "Page.getFrameTree" -> """{"frameTree":{"frame":{"id":"main"}}}"""
    "Page.createIsolatedWorld" -> """{"executionContextId":7}"""
    else -> """{"result":{"value":[{"url":"$url","rel":"icon","sizes":"","type":"$type"}]}}"""
}

internal fun testPng(width: Int, height: Int): ByteArray {
    val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, 0xFF3366CC.toInt())
    return java.io.ByteArrayOutputStream().use { output ->
        javax.imageio.ImageIO.write(image, "png", output)
        output.toByteArray()
    }
}
