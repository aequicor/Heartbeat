package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.browser.api.browserSearchUrl
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalBrowserAgentPolicy
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalOperation
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowser
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalBrowserSafetyTest {
    @Test
    fun `typing and keys reject a changed focus or document without dispatching input`() = runTest {
        for (operation in listOf(ExternalOperation.Type, ExternalOperation.Key)) {
            for (documentChanges in listOf(false, true)) {
                val fixture = Fixture(this)
                fixture.open()
                val expected = fixture.session.snapshot(OWNER, operation, input())
                if (documentChanges) fixture.browser.document = "reloaded" else fixture.browser.focus = "payment"
                val result = fixture.session.execute(OWNER, operation, input(), expected)
                assertNotNull(result.error)
                assertTrue(fixture.browser.commands.none { it.first.startsWith("Input.") })
            }
        }
    }

    @Test
    fun `unchanged reviewed focus receives the exact text and key`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        for (operation in listOf(ExternalOperation.Type, ExternalOperation.Key)) {
            val expected = fixture.session.snapshot(OWNER, operation, input())
            assertNull(fixture.session.execute(OWNER, operation, input(), expected).error)
        }
        val commands = fixture.browser.commands.filter { it.first.startsWith("Input.") }
        assertEquals(
            listOf("Input.insertText", "Input.dispatchKeyEvent", "Input.dispatchKeyEvent"),
            commands.map { it.first },
        )
        assertEquals(JsonPrimitive("draft"), commands.first().second["text"])
    }

    @Test
    fun `review observations preserve the agent read and its element references`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        val read = fixture.session.execute(OWNER, ExternalOperation.Read, tab())
        val args = buildJsonObject {
            put("tabId", TAB)
            put("snapshotId", read.data.getValue("snapshotId"))
            put("element", 0)
        }
        val expected = fixture.session.snapshot(OWNER, ExternalOperation.Click, args)
        assertEquals(expected, fixture.session.snapshot(OWNER, ExternalOperation.Click, args))
        assertNull(fixture.session.execute(OWNER, ExternalOperation.Click, args, expected).error)
        val scripts = fixture.browser.commands.filter { it.first == "Runtime.evaluate" }
            .map { it.second.getValue("expression").jsonPrimitive.content }
        assertEquals(1, scripts.count { "window.__heartbeatElements=elements" in it })
        assertEquals(3, scripts.count { "heartbeatSafetySnapshot" in it })
        assertEquals(2, fixture.browser.commands.count { it.first == "Input.dispatchMouseEvent" })
    }

    @Test
    fun `revocation and reacquisition cannot reuse an old review`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        val expected = fixture.session.snapshot(OWNER, ExternalOperation.Type, input())
        fixture.browser.events.emit(ExternalTabEvent.Revoked(TAB))
        runCurrent()
        assertNotNull(fixture.session.execute(OWNER, ExternalOperation.Type, input(), expected).error)
        fixture.browser.active = ExternalTab(TAB, URL, "Example")
        assertNull(fixture.session.execute(OWNER, ExternalOperation.RequestTab, handover()).error)
        assertNotNull(fixture.session.execute(OWNER, ExternalOperation.Type, input(), expected).error)
        assertTrue(fixture.browser.commands.none { it.first.startsWith("Input.") })
    }

    @Test
    fun `handover binds page evidence and never controls a changed active page`() = runTest {
        val fixture = Fixture(this)
        fixture.browser.active = ExternalTab(TAB, URL, "Example")
        val expected = fixture.session.snapshot(OWNER, ExternalOperation.RequestTab, handover())
        fixture.browser.content = "Different action"
        assertNotNull(fixture.session.execute(OWNER, ExternalOperation.RequestTab, handover(), expected).error)
        assertFalse(fixture.browser.controlled)
        val refreshed = fixture.session.snapshot(OWNER, ExternalOperation.RequestTab, handover())
        assertNull(fixture.session.execute(OWNER, ExternalOperation.RequestTab, handover(), refreshed).error)
        assertTrue(fixture.browser.controlled)
    }

    @Test
    fun `handover cannot review another tab than the host approval selected`() = runTest {
        val fixture = Fixture(this)
        fixture.browser.active = ExternalTab("other", URL, "Example")
        assertFailsWith<IllegalStateException> {
            fixture.session.snapshot(OWNER, ExternalOperation.RequestTab, handover())
        }
        assertFalse(fixture.browser.controlled)
    }

    @Test
    fun `private navigation refuses a changed document before navigating`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        val args = buildJsonObject {
            put("tabId", TAB)
            put("url", "http://localhost:8080")
        }
        val expected = fixture.session.snapshot(OWNER, ExternalOperation.Open, args)
        fixture.browser.document = "other"
        assertNotNull(fixture.session.execute(OWNER, ExternalOperation.Open, args, expected).error)
        assertTrue(fixture.browser.navigations.isEmpty())
    }

    @Test
    fun `new tab review expires after control was disabled`() = runTest {
        val fixture = Fixture(this)
        val args = buildJsonObject { put("url", "http://localhost:8080") }
        val expected = fixture.session.snapshot(OWNER, ExternalOperation.NewTab, args)
        fixture.policy.value = false
        runCurrent()
        fixture.policy.value = true
        runCurrent()
        assertNotNull(fixture.session.execute(OWNER, ExternalOperation.NewTab, args, expected).error)
        assertEquals(0, fixture.browser.opened)
    }

    @Test
    fun `private new tab requires a pinned browser and suggests a stable blank tab first`() = runTest {
        val fixture = Fixture(this)
        fixture.browser.isBound = false
        val failure = assertFailsWith<IllegalStateException> {
            fixture.session.snapshot(
                OWNER,
                ExternalOperation.NewTab,
                buildJsonObject { put("url", "http://localhost:8080") },
            )
        }
        assertEquals("Open a blank tab first, then use ex_control_open for this address", failure.message)
        assertEquals(0, fixture.browser.opened)
        fixture.open()
        assertEquals(1, fixture.browser.opened)
    }

    @Test
    fun `reload evidence includes the submission transition and rejects a changed history entry`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        val expected = fixture.session.snapshot(OWNER, ExternalOperation.Reload, tab())
        assertEquals(JsonPrimitive("form_submit"), expected.getValue("reloadEntry").jsonObject["transitionType"])
        fixture.browser.transition = "typed"
        assertNotNull(fixture.session.execute(OWNER, ExternalOperation.Reload, tab(), expected).error)
        assertTrue(fixture.browser.commands.none { it.first == "Page.reload" })
    }

    @Test
    fun `external search uses the common encoded search address`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        val result = fixture.session.execute(
            OWNER,
            ExternalOperation.Search,
            buildJsonObject {
                put("tabId", TAB)
                put("query", "one & two")
            },
        )
        assertNull(result.error)
        assertEquals(listOf(browserSearchUrl("one & two")), fixture.browser.navigations)
    }

    private class Fixture(test: TestScope) {
        val browser = Browser()
        val policy = MutableStateFlow(true)
        val session = ExternalBrowserSession(
            browser,
            object : ExternalBrowserAgentPolicy {
                override val availability = policy
            },
            Profile(test.backgroundScope),
        )

        init {
            test.runCurrent()
        }

        suspend fun open() {
            assertNull(session.execute(OWNER, ExternalOperation.NewTab, JsonObject(emptyMap())).error)
        }
    }

    private class Browser : ExternalBrowser {
        var active: ExternalTab? = null
        var document = "doc"
        var focus = "search"
        var content = "Example form"
        var transition = "form_submit"
        var controlled = false
        var isBound = true
        var opened = 0
        val commands = mutableListOf<Pair<String, JsonObject>>()
        val navigations = mutableListOf<String>()
        override val state = MutableStateFlow<ExternalBrowserState>(ExternalBrowserState.Connected("Chrome", "140"))
        override val events = MutableSharedFlow<ExternalTabEvent>(extraBufferCapacity = 16)
        override fun bindSession(owner: String, connectionId: String): AutoCloseable = AutoCloseable {}
        override fun isExternalSession(owner: String): Boolean = isBound
        override suspend fun activeTab(owner: String?): ExternalTab? = active
        override suspend fun readActiveTab(owner: String): JsonObject = buildJsonObject {
            put("tabId", checkNotNull(active).id)
            put("url", checkNotNull(active).url)
            put("text", content)
        }
        override suspend fun openTab(owner: String): ExternalTab {
            opened++
            return ExternalTab(TAB, URL, "Example")
        }
        override suspend fun recoverTab(owner: String, tabId: String, expectedUrl: String): ExternalTab =
            control(tabId, expectedUrl)
        override suspend fun control(tabId: String, expectedUrl: String): ExternalTab {
            controlled = true
            return checkNotNull(active)
        }
        override suspend fun navigate(tabId: String, url: String): ExternalTab {
            navigations += url
            return ExternalTab(tabId, url, "Page")
        }
        override suspend fun activate(tabId: String) = Unit
        override suspend fun release(tabId: String) = Unit
        override suspend fun close(tabId: String) = Unit
        override suspend fun resolveDocument(tabId: String, requestId: String, isAllowed: Boolean) = Unit
        override suspend fun devTools(tabId: String, method: String, params: JsonObject): String {
            commands += method to params
            return when (method) {
                "Page.getFrameTree" -> """{"frameTree":{"frame":{"id":"frame"}}}"""

                "Page.createIsolatedWorld" -> """{"executionContextId":7}"""

                "Page.getNavigationHistory" ->
                    """{"currentIndex":0,"entries":[{"id":1,"url":"$URL","transitionType":"$transition"}]}"""

                "Runtime.evaluate" -> buildJsonObject {
                    put("result", buildJsonObject { put("value", evaluate(params)) })
                }.toString()

                else -> "{}"
            }
        }
        private fun evaluate(params: JsonObject): JsonObject {
            val script = params.getValue("expression").jsonPrimitive.content
            return when {
                "heartbeatSafetySnapshot" in script -> buildJsonObject {
                    put("view", geometry())
                    put("content", content)
                    put("focus", buildJsonObject { put("node", focus) })
                }

                "querySelectorAll" in script -> buildJsonObject {
                    put("view", geometry())
                    put("url", URL)
                    put("title", "Example")
                }

                "elementFromPoint" in script -> buildJsonObject {
                    put("x", 40)
                    put("y", 30)
                    put("isChanged", false)
                    put("isVisible", true)
                    put("isTarget", true)
                }

                else -> geometry()
            }
        }
        private fun geometry(): JsonObject = buildJsonObject {
            put("document", document)
            put("url", URL)
            put("width", 800)
            put("height", 600)
            put("x", 0)
            put("y", 0)
        }
    }

    private class Profile(override val coroutineScope: CoroutineScope) : ScopeHandle {
        override val name = "test/profile"
        override val savedState: ScopeSavedState get() = error("Unused")
        override val isClosed = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
    }

    private companion object {
        const val OWNER = "session"
        const val TAB = "tab"
        const val URL = "https://example.com"
        fun tab() = buildJsonObject { put("tabId", TAB) }
        fun input() = buildJsonObject {
            put("tabId", TAB)
            put("text", "draft")
            put("key", "Enter")
        }
        fun handover() = buildJsonObject {
            put("approvedTabId", TAB)
            put("approvedUrl", URL)
        }
    }
}
