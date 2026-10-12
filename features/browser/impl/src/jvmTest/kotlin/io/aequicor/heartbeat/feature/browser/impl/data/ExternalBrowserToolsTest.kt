package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalBrowserControl
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalOperation
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeAgentControl
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEnabled
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserState
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTab
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTabEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalBrowserToolsTest {
    private val context = AgentToolContext(
        SessionRef(EngineId("pi"), SessionSourceId("local"), "trusted-session"),
        workspace = null,
        turn = TurnId("turn"),
    )
    private val control = FakeControl()
    private val browser = FakeBrowser()
    private val toggles = Toggles()
    private val tools = ExternalBrowserTools(lazyOf(control), lazyOf(browser), toggles)

    @Test
    fun `tools are declared only with both bridge flags and keep their trust category`() = runTest {
        val specs = tools.specifications(null)

        assertEquals(13, specs.size)
        assertTrue(specs.all { it.name.startsWith("ex_control_") })
        assertEquals(AgentToolAction.Command, specs.single { it.name == "ex_control_click" }.action)
        assertEquals(AgentToolAction.Read, specs.single { it.name == "ex_control_read" }.action)
        assertEquals(13, tools.catalog.size)
        toggles.values[ChromiumBridgeAgentControl.key] = false
        assertTrue(tools.specifications(null).isEmpty())
        assertTrue(tools.execute(context, "ex_control_tabs", JsonObject(emptyMap())).isError)
        assertTrue(control.calls.isEmpty())
    }

    @Test
    fun `handing over a tab always asks and binds the approval to the active page`() = runTest {
        val spec = tools.specifications(null).single { it.name == "ex_control_request_tab" }
        browser.active = ExternalTab("7", "https://mail.example/inbox", "Inbox")

        val approval = tools.approval(context.copy(trust = TrustLevel.Full), spec, JsonObject(emptyMap()))
        val isAsked = tools.requiresDecision(context.copy(trust = TrustLevel.Full), spec, JsonObject(emptyMap()))
        val forged = buildJsonObject { put("approvedTabId", "99") }
        val result = tools.execute(context.copy(authorization = approval), spec.name, forged)
        val unapproved = tools.execute(context, spec.name, JsonObject(emptyMap()))

        assertTrue(isAsked)
        assertEquals("tab:7|https://mail.example/inbox", approval.binding)
        assertTrue("«Inbox»" in approval.description.orEmpty())
        assertFalse(result.isError)
        assertEquals(
            buildJsonObject {
                put("approvedTabId", "7")
                put("approvedUrl", "https://mail.example/inbox")
            },
            control.calls.single().third,
        )
        assertTrue(unapproved.isError)
    }

    @Test
    fun `private addresses ask unless the session has full trust`() = runTest {
        val spec = tools.specifications(null).single { it.name == "ex_control_open" }
        val local = buildJsonObject {
            put("tabId", "t1")
            put("url", "localhost:8080")
        }
        val public = buildJsonObject {
            put("tabId", "t1")
            put("url", "https://example.com")
        }

        assertTrue(tools.requiresDecision(context, spec, local))
        assertFalse(tools.requiresDecision(context.copy(trust = TrustLevel.Full), spec, local))
        assertFalse(tools.requiresDecision(context, spec, public))
        assertTrue("https://localhost:8080" in tools.approval(context, spec, local).description.orEmpty())
    }

    @Test
    fun `input approvals name the owned tab and the element of the latest read`() = runTest {
        control.answer = BrowserResult(
            "r",
            buildJsonObject {
                put("tabId", "t1")
                put("snapshotId", "s1")
                put(
                    "elements",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", 0)
                                put("tag", "BUTTON")
                                put("label", "Pay")
                            },
                        )
                    },
                )
            },
        )
        tools.execute(context, "ex_control_read", buildJsonObject { put("tabId", "t1") })
        val click = tools.specifications(null).single { it.name == "ex_control_click" }

        val approval = tools.approval(
            context,
            click,
            buildJsonObject {
                put("tabId", "t1")
                put("snapshotId", "s1")
                put("element", 0)
            },
        )

        assertEquals("Клик во внешнем браузере", approval.title)
        assertTrue("BUTTON «Pay»" in approval.description.orEmpty(), approval.description)
        assertTrue("Вкладка: https://shop.example/" in approval.description.orEmpty())
    }

    @Test
    fun `screenshots come back as images and errors as tool errors`() = runTest {
        control.answer = BrowserResult("r", JsonObject(mapOf("tabId" to JsonPrimitive("t1"))), imageBase64 = "AAAA")
        val shot = tools.execute(context, "ex_control_screenshot", buildJsonObject { put("tabId", "t1") })
        control.answer = BrowserResult("r", JsonObject(emptyMap()), error = "Unknown tab; use ex_control_tabs")
        val failed = tools.execute(context, "ex_control_read", buildJsonObject { put("tabId", "x") })

        assertEquals("image/png", shot.images.single().mimeType)
        assertTrue(failed.isError)
        assertEquals("Unknown tab; use ex_control_tabs", failed.text)
        assertEquals(listOf(ExternalOperation.Screenshot, ExternalOperation.Read), control.calls.map { it.second })
    }

    private class FakeControl : ExternalBrowserControl {
        val calls = mutableListOf<Triple<String, ExternalOperation, JsonObject>>()
        var answer = BrowserResult("r", JsonObject(emptyMap()))

        override suspend fun execute(
            owner: String,
            operation: ExternalOperation,
            arguments: JsonObject,
        ): BrowserResult {
            calls += Triple(owner, operation, arguments)
            return answer
        }

        override suspend fun address(owner: String, tabId: String): String? =
            if (tabId == "t1") "https://shop.example/" else null
    }

    private class FakeBrowser : ExternalBrowser {
        var active: ExternalTab? = null
        override val state = MutableStateFlow<ExternalBrowserState>(ExternalBrowserState.Connected("Chrome", "140"))
        override val events: Flow<ExternalTabEvent> = emptyFlow()

        override suspend fun activeTab(): ExternalTab? = active

        override suspend fun openTab(): ExternalTab = error("Unused")

        override suspend fun control(tabId: String): ExternalTab = error("Unused")

        override suspend fun navigate(tabId: String, url: String): ExternalTab = error("Unused")

        override suspend fun activate(tabId: String) = Unit

        override suspend fun release(tabId: String) = Unit

        override suspend fun close(tabId: String) = Unit

        override suspend fun devTools(tabId: String, method: String, params: JsonObject): String = "{}"

        override suspend fun resolveDocument(tabId: String, requestId: String, isAllowed: Boolean) = Unit
    }

    private class Toggles : FeatureToggles {
        val values = mutableMapOf(ChromiumBridgeEnabled.key to true, ChromiumBridgeAgentControl.key to true)

        @Suppress("UNCHECKED_CAST") // The tools only read Boolean feature flags.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = (values[toggle.key] ?: toggle.default) as T

        @Suppress("UNCHECKED_CAST") // The fixture only declares Boolean feature flags.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(
            (values[toggle.key] ?: toggle.default) as T,
        )
    }
}
