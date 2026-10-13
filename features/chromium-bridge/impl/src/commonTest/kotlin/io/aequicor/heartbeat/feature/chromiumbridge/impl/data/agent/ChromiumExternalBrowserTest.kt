package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.agent

import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserException
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserFailure
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserState
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTab
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTabEvent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeClient
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeException
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeIncomingEvent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeSession
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChromiumExternalBrowserTest {
    private val sessions = MutableStateFlow<List<BridgeSession>>(emptyList())

    @Test
    fun `only an extension allowing agent control serves the external browser`() = runTest {
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val chatOnly = FakeSession(this, 1, setOf("chat"))
        val agent = FakeSession(this, 2, setOf("chat", "agentControl"))

        sessions.value = listOf(chatOnly)
        runCurrent()
        assertEquals(ExternalBrowserState.Unavailable, browser.state.value)
        val unavailable = assertFailsWith<ExternalBrowserException> { browser.activeTab() }
        assertEquals(ExternalBrowserFailure.Unavailable, unavailable.failure)

        sessions.value = listOf(chatOnly, agent)
        runCurrent()
        assertEquals(ExternalBrowserState.Connected("Google Chrome", "140"), browser.state.value)
        agent.client.value = agent.client.value?.copy(capabilities = setOf("chat"))
        runCurrent()
        assertEquals(ExternalBrowserState.Unavailable, browser.state.value)
    }

    @Test
    fun `calls carry tab ids and return tabs and DevTools results`() = runTest {
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val agent = FakeSession(this, 2, setOf("agentControl"))
        sessions.value = listOf(agent)
        runCurrent()
        agent.answers["tabs.open"] = buildJsonObject {
            putJsonObject("tab") {
                put("id", "17")
                put("url", "https://example.com/")
                put("title", "Example")
            }
        }
        agent.answers["cdp.send"] = buildJsonObject { putJsonObject("result") { put("data", "png") } }

        val tab = browser.openTab()
        val result = browser.devTools("2:17", "Page.captureScreenshot", buildJsonObject { put("format", "png") })

        assertEquals(ExternalTab("2:17", "https://example.com/", "Example"), tab)
        assertEquals("""{"data":"png"}""", result)
        assertEquals(listOf("tabs.open", "cdp.send"), agent.calls.map { it.first })
        assertEquals("17", (agent.calls[1].second["tabId"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `recovery lists owned tabs with connection handles and sends the owner on reattachment`() = runTest {
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val agent = FakeSession(this, 9, setOf("agentControl"))
        sessions.value = listOf(agent)
        runCurrent()
        agent.answers["tabs.owned"] = buildJsonObject {
            putJsonArray("tabs") {
                addJsonObject {
                    put("id", "17")
                    put("url", "https://example.com/")
                    put("title", "Example")
                }
            }
        }
        agent.answers["tabs.recover"] = buildJsonObject {
            putJsonObject("tab") {
                put("id", "17")
                put("url", "https://example.com/")
                put("title", "Example")
            }
        }
        val tab = browser.ownedTabs("owner").single()
        assertEquals("9:17", tab.id)
        assertEquals(tab, browser.recoverTab("owner", tab.id, tab.url))
        assertEquals(
            buildJsonObject {
                put("tabId", "17")
                put("owner", "owner")
                put("expectedUrl", tab.url)
            },
            agent.calls.last().second,
        )
        sessions.value = emptyList()
        assertFailsWith<ExternalBrowserException> { browser.ownedTabs("owner") }
    }

    @Test
    fun `cold acquisition installs revocation listeners before the first RPC`() = runTest {
        val agent = FakeSession(this, 9, setOf("agentControl"))
        sessions.value = listOf(agent)
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        agent.answers["tabs.open"] = buildJsonObject {
            putJsonObject("tab") { put("id", "17") }
        }
        agent.beforeReply = {
            agent.incoming.emit(BridgeIncomingEvent("tab.revoked", buildJsonObject { put("tabId", "17") }))
        }
        assertFailsWith<ExternalBrowserException> { browser.openTab("owner") }
    }

    @Test
    fun `panel turns stay on their browser and cannot fall back after disconnection`() = runTest {
        val first = FakeSession(this, 1, setOf("agentControl"))
        val second = FakeSession(this, 2, setOf("agentControl"))
        sessions.value = listOf(first, second)
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val lease = browser.bindSession("owner", "1")
        browser.activeTab("owner")
        assertEquals("tabs.active", first.calls.single().first)
        assertEquals(emptyList(), second.calls)
        assertEquals(false, browser.isTabInSession("owner", "2:17"))
        sessions.value = listOf(second)
        assertFailsWith<ExternalBrowserException> { browser.activeTab("owner") }
        assertEquals(true, browser.isExternalSession("owner"))
        val replacement = browser.bindSession("owner", "2")
        lease.close()
        assertEquals(true, browser.isExternalSession("owner"))
        browser.activeTab("owner")
        assertEquals("tabs.active", second.calls.single().first)
        replacement.close()
        assertEquals(false, browser.isExternalSession("owner"))
    }

    @Test
    fun `extension refusals map to failures`() = runTest {
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val agent = FakeSession(this, 2, setOf("agentControl"))
        sessions.value = listOf(agent)
        runCurrent()
        agent.refusal = BridgeException(BridgeErrorCode.NO_SUCH_TAB, "Gone")

        val error = assertFailsWith<ExternalBrowserException> { browser.release("2:5") }

        assertEquals(ExternalBrowserFailure.NoSuchTab, error.failure)
    }

    @Test
    fun `tab events of the agent connection are typed`() = runTest {
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val agent = FakeSession(this, 2, setOf("agentControl"))
        sessions.value = listOf(agent)
        runCurrent()
        val received = mutableListOf<ExternalTabEvent>()
        backgroundScope.launch { browser.events.toList(received) }
        runCurrent()

        agent.incoming.emit(
            event("tab.changed") {
                put("url", "https://a/")
                put("title", "A")
                put("loading", true)
            },
        )
        agent.incoming.emit(
            event("guard.paused") {
                put("requestId", "r1")
                put("url", "http://10.0.0.1/")
                put("mainFrame", false)
            },
        )
        agent.incoming.emit(event("tab.revoked") {})
        agent.incoming.emit(BridgeIncomingEvent("tabs.summary", buildJsonObject { put("controlled", 1) }))
        runCurrent()

        assertEquals(
            listOf(
                ExternalTabEvent.Changed("2:9", "https://a/", "A", isLoading = true),
                ExternalTabEvent.DocumentRequested("2:9", "r1", "http://10.0.0.1/", isMainFrame = false),
                ExternalTabEvent.Revoked("2:9"),
            ),
            received,
        )
    }

    @Test
    fun `handles stay on their original browser and lost connections revoke them`() = runTest {
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val first = FakeSession(this, 1, setOf("agentControl"))
        val second = FakeSession(this, 2, setOf("agentControl"))
        first.answers["tabs.open"] = tabAnswer("9")
        second.answers["tabs.open"] = tabAnswer("9")
        sessions.value = listOf(first)
        runCurrent()
        val firstTab = browser.openTab()
        sessions.value = listOf(first, second)
        runCurrent()
        val secondTab = browser.openTab()
        browser.activate(firstTab.id)
        browser.activate(secondTab.id)
        assertEquals("1:9", firstTab.id)
        assertEquals("2:9", secondTab.id)
        assertEquals(listOf("tabs.open", "tabs.activate"), first.calls.map { it.first })
        assertEquals(listOf("tabs.open", "tabs.activate"), second.calls.map { it.first })

        val received = mutableListOf<ExternalTabEvent>()
        backgroundScope.launch { browser.events.toList(received) }
        runCurrent()
        first.incoming.emit(event("guard.paused") { put("requestId", "r1") })
        runCurrent()
        assertEquals("1:9", received.single().tabId)
        sessions.value = listOf(second)
        runCurrent()
        assertEquals(ExternalTabEvent.Revoked("1:9"), received.last())
        assertFailsWith<ExternalBrowserException> { browser.activate(firstTab.id) }
        assertEquals(2, second.calls.size)
    }

    @Test
    fun `control transmits the approved URL to the original connection`() = runTest {
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val first = FakeSession(this, 1, setOf("agentControl"))
        val second = FakeSession(this, 2, setOf("agentControl"))
        first.answers["tabs.control"] = tabAnswer("9")
        sessions.value = listOf(first, second)
        runCurrent()
        browser.control("1:9", "https://example.com/")
        assertEquals("https://example.com/", first.calls.single().second["expectedUrl"]?.toString()?.trim('"'))
        assertEquals(emptyList(), second.calls)
    }

    @Test
    fun `revocation before an acquisition reply cannot restore the handle`() = runTest {
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val agent = FakeSession(this, 1, setOf("agentControl"))
        val reply = CompletableDeferred<Unit>()
        agent.beforeReply = { reply.await() }
        agent.answers["tabs.open"] = tabAnswer("9")
        sessions.value = listOf(agent)
        runCurrent()
        val opening = async { assertFailsWith<ExternalBrowserException> { browser.openTab() } }
        runCurrent()
        agent.incoming.emit(event("tab.revoked") {})
        runCurrent()
        reply.complete(Unit)
        assertEquals(ExternalBrowserFailure.NoSuchTab, opening.await().failure)
    }

    @Test
    fun `capability loss invalidates pending acquisition even after reenable`() = runTest {
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val agent = FakeSession(this, 1, setOf("agentControl"))
        val reply = CompletableDeferred<Unit>()
        agent.beforeReply = { reply.await() }
        agent.answers["tabs.open"] = tabAnswer("9")
        sessions.value = listOf(agent)
        runCurrent()
        val opening = async { assertFailsWith<ExternalBrowserException> { browser.openTab() } }
        runCurrent()
        agent.client.value = agent.client.value?.copy(capabilities = emptySet())
        runCurrent()
        agent.client.value = agent.client.value?.copy(capabilities = setOf("agentControl"))
        runCurrent()
        reply.complete(Unit)
        assertEquals(ExternalBrowserFailure.NoSuchTab, opening.await().failure)
    }

    @Test
    fun `explicit release invalidates an outstanding navigation reply`() = runTest {
        val browser = ChromiumExternalBrowser(sessions, backgroundScope)
        val agent = FakeSession(this, 1, setOf("agentControl"))
        val reply = CompletableDeferred<Unit>()
        agent.beforeReply = { reply.await() }
        agent.answers["tabs.navigate"] = tabAnswer("9")
        sessions.value = listOf(agent)
        runCurrent()
        val navigating = async {
            assertFailsWith<ExternalBrowserException> { browser.navigate("1:9", "https://example.com/") }
        }
        runCurrent()
        agent.beforeReply = {}
        browser.release("1:9")
        reply.complete(Unit)
        assertEquals(ExternalBrowserFailure.NoSuchTab, navigating.await().failure)
    }

    private fun tabAnswer(id: String): JsonObject = buildJsonObject {
        putJsonObject("tab") {
            put("id", id)
            put("url", "https://example.com/")
            put("title", "Example")
        }
    }

    private fun event(name: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        BridgeIncomingEvent(
            name,
            buildJsonObject {
                put("tabId", "9")
                params()
            },
        )

    private class FakeSession(test: TestScope, override val id: Long, capabilities: Set<String>) : BridgeSession {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val answers = mutableMapOf<String, JsonObject>()
        val incoming = MutableSharedFlow<BridgeIncomingEvent>()
        var refusal: BridgeException? = null
        var beforeReply: suspend () -> Unit = {}
        override val origin: String = "chrome-extension://test/"
        override val client = MutableStateFlow<BridgeClient?>(
            BridgeClient(origin, "0.1.0", "Google Chrome", "140", capabilities),
        )
        override val events = incoming
        override val scope: CoroutineScope = test.backgroundScope

        override fun accept(client: BridgeClient) = Unit

        override suspend fun emit(event: String, params: JsonObject) = Unit

        override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
            calls += method to params
            refusal?.let { throw it }
            beforeReply()
            return answers[method] ?: JsonObject(emptyMap())
        }
    }
}
