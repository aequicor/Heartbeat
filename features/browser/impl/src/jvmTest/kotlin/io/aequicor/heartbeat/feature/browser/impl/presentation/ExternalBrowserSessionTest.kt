package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalBrowserAgentPolicy
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalOperation
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserException
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserFailure
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserState
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTab
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTabEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalBrowserSessionTest {
    private val browser = FakeExternalBrowser()
    private val policy = MutableStateFlow(true)

    @Test
    fun `a new tab is owned by its session and loads the requested page`() = runTest {
        val session = session(this)

        val opened = session.execute(OWNER, ExternalOperation.NewTab, args { put("url", "example.com") })
        val listedByOther = session.execute(OTHER, ExternalOperation.Tabs, JsonObject(emptyMap()))

        assertNull(opened.error)
        assertEquals(JsonPrimitive("t1"), opened.data["tabId"])
        assertEquals(listOf("openTab", "navigate t1 https://example.com"), browser.calls)
        assertEquals("""{"tabs":[]}""", listedByOther.data.toString())
        val foreign = session.execute(OTHER, ExternalOperation.Read, args { put("tabId", "t1") })
        assertEquals("Unknown tab; use ex_control_tabs", foreign.error)
    }

    @Test
    fun `listing restores agent tabs after reconnect and keeps them private to their owner`() = runTest {
        val session = session(this)
        session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))
        browser.events.emit(ExternalTabEvent.Revoked("t1"))
        browser.state.value = ExternalBrowserState.Unavailable
        runCurrent()
        browser.state.value = ExternalBrowserState.Connected("Chrome", "140")
        browser.recoverable = listOf(ExternalTab("restored", "https://example.com/", "Example"))
        browser.active = browser.recoverable.single()
        runCurrent()

        val listed = session.execute(OWNER, ExternalOperation.Tabs, JsonObject(emptyMap()))

        assertNull(listed.error)
        assertTrue(listed.data.toString().contains("restored"))
        assertTrue(listed.data.toString().contains("\"isUserTab\":false"))
        assertTrue("control restored" in browser.calls)
        val other = session.execute(OTHER, ExternalOperation.Tabs, JsonObject(emptyMap()))
        assertEquals("[]", other.data["tabs"].toString())
    }

    @Test
    fun `a browser-bound turn cannot list or act on its owners tabs in another browser`() = runTest {
        val session = session(this)
        session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))
        session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))
        browser.allowedTab = "t2"
        val listed = session.execute(OWNER, ExternalOperation.Tabs, JsonObject(emptyMap()))
        assertNull(listed.error)
        assertTrue(listed.data.toString().contains("t2"))
        assertTrue(!listed.data.toString().contains("t1"))
        assertNotNull(session.execute(OWNER, ExternalOperation.Read, args { put("tabId", "t1") }).error)
        assertNull(session.address(OWNER, "t1"))
    }

    @Test
    fun `listing exposes the current user page without acquiring its control`() = runTest {
        val session = session(this)
        browser.active = ExternalTab("user", "https://example.com/", "Current page")
        val result = session.execute(OWNER, ExternalOperation.Tabs, JsonObject(emptyMap()))
        assertTrue(result.data["activeTab"].toString().contains("Current page"))
        assertEquals("[]", result.data["tabs"].toString())
        assertTrue(browser.calls.isEmpty())
        assertNotNull(session.execute(OWNER, ExternalOperation.Click, args { put("tabId", "user") }).error)
    }

    @Test
    fun `page reading goes through the DevTools driver of the tab`() = runTest {
        val session = session(this)
        session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))

        val read = session.execute(OWNER, ExternalOperation.Read, args { put("tabId", "t1") })
        val screenshot = session.execute(OWNER, ExternalOperation.Screenshot, args { put("tabId", "t1") })

        assertEquals("Example", read.data["title"]?.jsonPrimitive?.content)
        assertNotNull(screenshot.imageBase64)
        assertTrue("activate t1" in browser.calls)
        assertTrue(browser.devTools.any { it == "t1 Page.createIsolatedWorld" })
    }

    @Test
    fun `the guard keeps agent tabs on the public web unless the user approved the origin`() = runTest {
        val session = session(this)
        session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))
        runCurrent()

        browser.events.emit(ExternalTabEvent.DocumentRequested("t1", "r1", "https://example.org/", isMainFrame = true))
        browser.events.emit(ExternalTabEvent.DocumentRequested("t1", "r2", "http://192.168.1.1/", isMainFrame = true))
        browser.events.emit(ExternalTabEvent.DocumentRequested("t9", "r3", "https://example.org/", isMainFrame = true))
        runCurrent()
        session.execute(
            OWNER,
            ExternalOperation.Open,
            args {
                put("tabId", "t1")
                put("url", "http://192.168.1.1/admin")
            },
        )
        browser.events.emit(ExternalTabEvent.DocumentRequested("t1", "r4", "http://192.168.1.1/", isMainFrame = true))
        runCurrent()
        val read = session.execute(OWNER, ExternalOperation.Read, args { put("tabId", "t1") })

        assertEquals(listOf("r1" to true, "r2" to false, "r3" to false, "r4" to true), browser.resolved)
        assertTrue(read.data.containsKey("blockedNavigation").not())
    }

    @Test
    fun `a handed over user tab approves the page it shows`() = runTest {
        val session = session(this)
        browser.active = ExternalTab("u5", "http://intranet.local/wiki", "Wiki")
        runCurrent()

        val approval = args {
            put("approvedTabId", "u5")
            put("approvedUrl", "http://intranet.local/wiki")
        }
        val stale = session.execute(OWNER, ExternalOperation.RequestTab, JsonObject(emptyMap()))
        val handed = session.execute(OWNER, ExternalOperation.RequestTab, approval)
        browser.events.emit(ExternalTabEvent.DocumentRequested("u5", "r1", "http://intranet.local/page", true))
        runCurrent()

        assertEquals("The active tab changed since the approval; ask again", stale.error)
        assertEquals(JsonPrimitive(true), handed.data["isUserTab"])
        assertEquals(listOf("control u5"), browser.calls)
        assertEquals(listOf("r1" to true), browser.resolved)
        assertEquals(
            "Another chat controls this tab",
            session.execute(OTHER, ExternalOperation.RequestTab, approval).error,
        )
    }

    @Test
    fun `revoked tabs are forgotten and turning agent control off releases the rest`() = runTest {
        val session = session(this)
        session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))
        session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))
        runCurrent()

        browser.events.emit(ExternalTabEvent.Revoked("t1"))
        runCurrent()
        policy.value = false
        runCurrent()

        assertEquals(
            "Agent control is disabled",
            session.execute(OWNER, ExternalOperation.Tabs, JsonObject(emptyMap())).error,
        )
        assertEquals(listOf("release t2"), browser.calls.filter { it.startsWith("release") })
    }

    @Test
    fun `extension refusals come back as errors`() = runTest {
        val session = session(this)
        browser.refusal = ExternalBrowserException(
            ExternalBrowserFailure.Unavailable,
            "No browser allows agent control",
        )

        val result = session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))

        assertEquals("No browser allows agent control", result.error)
    }

    @Test
    fun `handover rejects a URL changed during acquisition and releases the tab`() = runTest {
        val session = session(this)
        browser.active = ExternalTab("u5", "https://approved.example/", "Approved")
        browser.beforeControl = { browser.active = ExternalTab("u5", "http://private.local/", "Private") }
        val result = session.execute(OWNER, ExternalOperation.RequestTab, approval())
        assertEquals("The active tab changed since the approval; ask again", result.error)
        assertEquals(listOf("control u5", "release u5"), browser.calls)
        assertNull(session.address(OWNER, "u5"))
    }

    @Test
    fun `concurrent handovers cannot replace the first owner`() = runTest {
        val session = session(this)
        browser.active = ExternalTab("u5", "https://approved.example/", "Approved")
        val reply = CompletableDeferred<Unit>()
        browser.beforeControl = { reply.await() }
        val first = async { session.execute(OWNER, ExternalOperation.RequestTab, approval()) }
        runCurrent()
        val second = session.execute(OTHER, ExternalOperation.RequestTab, approval())
        assertEquals("Tab handover already in progress", second.error)
        reply.complete(Unit)
        assertNull(first.await().error)
        assertEquals("https://approved.example/", session.address(OWNER, "u5"))
        assertNull(session.address(OTHER, "u5"))
        assertEquals(listOf("control u5"), browser.calls)
    }

    @Test
    fun `disabling control during open releases its eventual tab`() = runTest {
        val session = session(this)
        val reply = CompletableDeferred<Unit>()
        browser.beforeOpen = { reply.await() }
        val opening = async { session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap())) }
        runCurrent()
        policy.value = false
        runCurrent()
        reply.complete(Unit)
        assertEquals("Agent control is disabled", opening.await().error)
        assertNull(session.address(OWNER, "t1"))
        assertEquals(listOf("openTab", "release t1"), browser.calls)
    }

    @Test
    fun `cancelled open releases the acquired tab before completing`() = runTest {
        val session = session(this)
        val reply = CompletableDeferred<Unit>()
        browser.beforeOpen = { reply.await() }
        val opening = launch { session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap())) }
        runCurrent()
        opening.cancel()
        reply.complete(Unit)
        opening.join()
        assertNull(session.address(OWNER, "t1"))
        assertEquals(listOf("openTab", "release t1"), browser.calls)
    }

    @Test
    fun `revocation during handover prevents ownership registration`() = runTest {
        val session = session(this)
        browser.active = ExternalTab("u5", "https://approved.example/", "Approved")
        val reply = CompletableDeferred<Unit>()
        browser.beforeControl = { reply.await() }
        val handover = async { session.execute(OWNER, ExternalOperation.RequestTab, approval()) }
        runCurrent()
        browser.events.emit(ExternalTabEvent.Revoked("u5"))
        runCurrent()
        reply.complete(Unit)
        assertEquals("Tab control ended during acquisition", handover.await().error)
        assertNull(session.address(OWNER, "u5"))
        assertEquals(listOf("control u5", "release u5"), browser.calls)
    }

    @Test
    fun `pending new tabs count toward the limit`() = runTest {
        val session = session(this)
        val reply = CompletableDeferred<Unit>()
        browser.beforeOpen = { reply.await() }
        val opening = List(20) { async { session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap())) } }
        runCurrent()
        val extra = session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))
        assertEquals("Release or close a tab before opening another", extra.error)
        assertEquals(20, browser.calls.size)
        reply.complete(Unit)
        opening.forEach { assertNull(it.await().error) }
    }

    @Test
    fun `the first operation waits for the initial policy observation`() = runTest {
        val session = session(this, ready = false)
        val opened = session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))
        assertNull(opened.error)
        assertEquals("t1", opened.data["tabId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `handover waits until the previous owner release finishes`() = runTest {
        val session = session(this)
        session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap()))
        browser.active = ExternalTab("t1", "https://approved.example/", "Approved")
        val approval = args {
            put("approvedTabId", "t1")
            put("approvedUrl", "https://approved.example/")
        }
        val reply = CompletableDeferred<Unit>()
        browser.beforeRelease = { reply.await() }
        val releasing = async {
            session.execute(OWNER, ExternalOperation.Release, args { put("tabId", "t1") })
        }
        runCurrent()
        val racing = session.execute(OTHER, ExternalOperation.RequestTab, approval)
        assertEquals("Tab handover already in progress", racing.error)
        reply.complete(Unit)
        assertNull(releasing.await().error)
        assertNull(session.execute(OTHER, ExternalOperation.RequestTab, approval).error)
        assertEquals("https://approved.example/", session.address(OTHER, "t1"))
        assertEquals(listOf("openTab", "release t1", "control t1"), browser.calls)
    }

    private fun approval() = args {
        put("approvedTabId", "u5")
        put("approvedUrl", "https://approved.example/")
    }

    private fun session(test: TestScope, ready: Boolean = true) = ExternalBrowserSession(
        browser,
        object : ExternalBrowserAgentPolicy {
            override val availability = policy
        },
        Profile(test.backgroundScope),
    ).also { if (ready) test.runCurrent() }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    private class FakeExternalBrowser : ExternalBrowser {
        val calls = mutableListOf<String>()
        val devTools = mutableListOf<String>()
        val resolved = mutableListOf<Pair<String, Boolean>>()
        var active: ExternalTab? = null
        var allowedTab: String? = null
        override fun isTabInSession(owner: String, tabId: String): Boolean = allowedTab?.let { it == tabId } ?: true

        var recoverable: List<ExternalTab> = emptyList()
        override suspend fun ownedTabs(owner: String): List<ExternalTab> =
            if (owner == OWNER) recoverable else emptyList()

        var refusal: ExternalBrowserException? = null
        var beforeOpen: suspend () -> Unit = {}
        var beforeControl: suspend () -> Unit = {}
        var beforeRelease: suspend () -> Unit = {}
        private var next = 0
        override val state = MutableStateFlow<ExternalBrowserState>(ExternalBrowserState.Connected("Chrome", "140"))
        override val events = MutableSharedFlow<ExternalTabEvent>(extraBufferCapacity = 16)

        override fun bindSession(owner: String, connectionId: String): AutoCloseable = AutoCloseable { }

        override suspend fun readActiveTab(owner: String): JsonObject = JsonObject(emptyMap())

        override suspend fun activeTab(owner: String?): ExternalTab? = active

        override suspend fun recoverTab(owner: String, tabId: String, expectedUrl: String): ExternalTab =
            control(tabId, expectedUrl)

        override suspend fun openTab(owner: String): ExternalTab {
            refusal?.let { throw it }
            calls += "openTab"
            beforeOpen()
            return ExternalTab("t${++next}", "", "")
        }

        override suspend fun control(tabId: String, expectedUrl: String): ExternalTab {
            calls += "control $tabId"
            beforeControl()
            return checkNotNull(active)
        }

        override suspend fun navigate(tabId: String, url: String): ExternalTab {
            calls += "navigate $tabId $url"
            return ExternalTab(tabId, url, "Page")
        }

        override suspend fun activate(tabId: String) {
            calls += "activate $tabId"
        }

        override suspend fun release(tabId: String) {
            calls += "release $tabId"
            beforeRelease()
        }

        override suspend fun close(tabId: String) {
            calls += "close $tabId"
        }

        override suspend fun devTools(tabId: String, method: String, params: JsonObject): String {
            devTools += "$tabId $method"
            return when (method) {
                "Page.getFrameTree" -> """{"frameTree":{"frame":{"id":"frame","loaderId":"loader"}}}"""
                "Page.createIsolatedWorld" -> """{"executionContextId":7}"""
                "Page.captureScreenshot" -> """{"data":"iVBORw0KGgoAAAANSUhEUgAAAyAAAAJY"}"""
                "Runtime.evaluate" -> """{"result":{"value":${evaluate(params)}}}"""
                else -> "{}"
            }
        }

        override suspend fun resolveDocument(tabId: String, requestId: String, isAllowed: Boolean) {
            resolved += requestId to isAllowed
        }

        private fun evaluate(params: JsonObject): String {
            val expression = params.getValue("expression").jsonPrimitive.content
            val geometry = """{"document":"doc","url":"https://example.com","width":800,"height":600,"x":0,"y":0}"""
            return if ("querySelectorAll" in expression) {
                """{"view":$geometry,"url":"https://example.com","title":"Example","content":"Text"}"""
            } else {
                geometry
            }
        }
    }

    private class Profile(override val coroutineScope: CoroutineScope) : ScopeHandle {
        override val name = "test/profile"
        override val savedState: ScopeSavedState get() = error("Unused")
        override val isClosed = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
    }

    private companion object {
        const val OWNER = "session-a"
        const val OTHER = "session-b"
    }
}
