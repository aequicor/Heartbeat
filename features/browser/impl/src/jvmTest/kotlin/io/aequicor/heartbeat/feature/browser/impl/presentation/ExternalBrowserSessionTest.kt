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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

        val handed = session.execute(OWNER, ExternalOperation.RequestTab, JsonObject(emptyMap()))
        browser.events.emit(ExternalTabEvent.DocumentRequested("u5", "r1", "http://intranet.local/page", true))
        runCurrent()

        assertEquals(JsonPrimitive(true), handed.data["isUserTab"])
        assertEquals(listOf("control u5"), browser.calls)
        assertEquals(listOf("r1" to true), browser.resolved)
        assertEquals(
            "Another chat controls this tab",
            session.execute(OTHER, ExternalOperation.RequestTab, JsonObject(emptyMap())).error,
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
            """{"tabs":[]}""",
            session.execute(OWNER, ExternalOperation.Tabs, JsonObject(emptyMap())).data.toString(),
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

    private fun session(test: TestScope) = ExternalBrowserSession(
        browser,
        object : ExternalBrowserAgentPolicy {
            override val availability = policy
        },
        Profile(test.backgroundScope),
    ).also { test.runCurrent() }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    private class FakeExternalBrowser : ExternalBrowser {
        val calls = mutableListOf<String>()
        val devTools = mutableListOf<String>()
        val resolved = mutableListOf<Pair<String, Boolean>>()
        var active: ExternalTab? = null
        var refusal: ExternalBrowserException? = null
        private var next = 0
        override val state = MutableStateFlow<ExternalBrowserState>(ExternalBrowserState.Connected("Chrome", "140"))
        override val events = MutableSharedFlow<ExternalTabEvent>(extraBufferCapacity = 16)

        override suspend fun activeTab(): ExternalTab? = active

        override suspend fun openTab(): ExternalTab {
            refusal?.let { throw it }
            calls += "openTab"
            return ExternalTab("t${++next}", "", "")
        }

        override suspend fun control(tabId: String): ExternalTab {
            calls += "control $tabId"
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
