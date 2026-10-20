package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    private var prompts = 0
    private val automatic = context.copy(
        target = EngineTarget(EngineId("pi"), EngineBindingId("provider"), ModelId("model")),
        decisionMode = AgentToolDecisionMode.AutomaticSafety,
        permissions = AgentToolPermissions {
            prompts++
            true
        },
    )

    @Test
    fun `browser references mean the source browser before creation and throughout the bound turn`() = runTest {
        val newScope = AgentToolScope(null, preselected = mapOf("external_browser" to setOf("connection")))
        val instructions = tools.instructions(newScope)
        assertTrue(instructions.contains("Any user reference to the browser"))
        assertTrue(instructions.contains("external browser hosting this chat"))
        assertTrue(instructions.contains("start with ex_control_active_tab"))
        assertFalse(instructions.contains("browser_*"))

        val scope = AgentToolScope(null, session = context.session)
        val lease = browser.bindSession(Json.encodeToString(context.session), "connection")
        assertEquals(instructions, tools.instructions(scope))
        browser.state.value = ExternalBrowserState.Unavailable
        assertEquals(instructions, tools.instructions(scope))
        lease.close()
        assertFalse(tools.instructions(scope).contains("external browser hosting this chat"))
    }

    @Test
    fun `source browser context survives unavailable tools without advertising undeclared calls`() = runTest {
        val scope = AgentToolScope(null, preselected = mapOf("external_browser" to setOf("connection")))
        assertTrue(tools.hasIndependentInstructions)
        for (declared in listOf(emptySet(), setOf("ex_control_tabs"))) {
            val instructions = tools.instructions(scope.copy(declared = declared))
            assertTrue(instructions.contains("external browser hosting this chat"))
            assertFalse(instructions.contains("ex_control_"))
        }
        toggles.values[ChromiumBridgeAgentControl.key] = false
        assertTrue(tools.instructions(scope).contains("external browser hosting this chat"))
        assertFalse(tools.instructions(scope).contains("ex_control_"))
        assertEquals("", tools.instructions(AgentToolScope(null)))
    }

    @Test
    fun `tools are declared only with both bridge flags and keep their trust category`() = runTest {
        val specs = tools.specifications(null)

        assertEquals(19, specs.size)
        assertTrue(specs.all { it.name.startsWith("ex_control_") })
        assertEquals(AgentToolAction.Command, specs.single { it.name == "ex_control_click" }.action)
        assertEquals(AgentToolAction.Read, specs.single { it.name == "ex_control_read" }.action)
        for (name in listOf("back", "forward", "reload")) {
            assertEquals(AgentToolAction.Command, specs.single { it.name == "ex_control_$name" }.action)
        }
        for (name in listOf("search", "activate")) {
            assertEquals(AgentToolAction.Read, specs.single { it.name == "ex_control_$name" }.action)
        }
        assertEquals(19, tools.catalog.size)
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
        assertTrue(tools.execute(context.copy(authorization = approval), spec.name, forged).isError)
        val result = tools.execute(context.copy(authorization = approval), spec.name, JsonObject(emptyMap()))
        val unapproved = tools.execute(context, spec.name, JsonObject(emptyMap()))

        assertTrue(isAsked)
        assertTrue(approval.binding.orEmpty().startsWith("external-reviewed:"))
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
        assertEquals(control.pageSnapshot, control.expected.single())
    }

    @Test
    fun `each operation declares only relevant arguments and a bounded key vocabulary`() = runTest {
        val required = mapOf(
            "tabs" to emptySet(),
            "active_tab" to emptySet(),
            "new_tab" to emptySet(),
            "request_tab" to emptySet(),
            "close_tab" to setOf("tabId"),
            "release" to setOf("tabId"),
            "open" to setOf("tabId", "url"),
            "search" to setOf("tabId", "query"),
            "back" to setOf("tabId"),
            "forward" to setOf("tabId"),
            "reload" to setOf("tabId"),
            "activate" to setOf("tabId"),
            "read" to setOf("tabId"),
            "screenshot" to setOf("tabId"),
            "click" to setOf("tabId", "snapshotId"),
            "type" to setOf("tabId", "text"),
            "key" to setOf("tabId", "key"),
            "scroll" to setOf("tabId", "snapshotId", "x", "y", "deltaY"),
            "drag" to setOf("tabId", "snapshotId", "x", "y", "toX", "toY"),
        )
        val specs = tools.specifications(null).associateBy { it.name.removePrefix("ex_control_") }
        assertEquals(required.keys, specs.keys)
        for ((name, fields) in required) {
            val schema = specs.getValue(name).inputSchema
            val optional = when (name) {
                "new_tab" -> setOf("url")
                "click" -> setOf("element", "x", "y")
                else -> emptySet()
            }
            assertEquals(fields, schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(), name)
            assertEquals(fields + optional, schema.getValue("properties").jsonObject.keys, name)
            assertEquals(JsonPrimitive(false), schema["additionalProperties"], name)
        }
        val key = specs.getValue("key").inputSchema.getValue("properties").jsonObject.getValue("key").jsonObject
        assertEquals(
            setOf(
                "Enter",
                "Tab",
                "Escape",
                "Backspace",
                "Delete",
                "ArrowLeft",
                "ArrowUp",
                "ArrowRight",
                "ArrowDown",
                "Home",
                "End",
            ),
            key.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            Json.parseToJsonElement("""[{"required":["element"]},{"required":["x","y"]}]"""),
            specs.getValue("click").inputSchema["anyOf"],
        )
    }

    @Test
    fun `authorized input executes with the exact page and arguments`() = runTest {
        val spec = tools.specifications(null).single { it.name == "ex_control_type" }
        val args = buildJsonObject {
            put("tabId", "t1")
            put("text", "Draft search terms")
        }
        val approval = tools.approval(automatic, spec, args)
        val caller = automatic.copy(authorization = approval)

        assertFalse(tools.execute(caller, spec.name, args).isError)
        assertEquals(Triple(Json.encodeToString(context.session), ExternalOperation.Type, args), control.calls.single())
        assertEquals(control.pageSnapshot, control.expected.single())
        assertEquals(0, prompts)
    }

    @Test
    fun `automatic hand-over reviews and executes the trusted active tab without prompting`() = runTest {
        browser.active = ExternalTab("7", "https://docs.example/guide", "Guide")
        val spec = tools.specifications(null).single { it.name == "ex_control_request_tab" }
        val forged = buildJsonObject {
            put("approvedTabId", "99")
            put("approvedUrl", "https://attacker.example/")
        }
        val approval = tools.approval(automatic, spec, forged)

        val result = tools.execute(automatic.copy(authorization = approval), spec.name, forged)

        assertFalse(result.isError)
        val effective = buildJsonObject {
            put("approvedTabId", "7")
            put("approvedUrl", "https://docs.example/guide")
        }
        assertEquals(effective, control.calls.single().third)
        assertTrue(control.snapshots.all { it.third == effective })
        assertEquals(control.pageSnapshot, control.expected.single())
        assertEquals(0, prompts)
    }

    @Test
    fun `hand-over cannot acquire a different active tab or a navigated page after review`() = runTest {
        val original = ExternalTab("7", "https://docs.example/guide", "Guide")
        browser.active = original
        val spec = tools.specifications(null).single { it.name == "ex_control_request_tab" }
        val args = JsonObject(emptyMap())
        val approval = tools.approval(automatic, spec, args)
        val caller = automatic.copy(authorization = approval)
        for (replacement in listOf(original.copy(id = "8"), original.copy(url = "https://docs.example/account"))) {
            browser.active = replacement
            assertTrue(tools.execute(caller, spec.name, args).isError)
        }
        assertTrue(control.calls.isEmpty())
        assertEquals(0, prompts)
    }

    @Test
    fun `missing active tab refuses hand-over without requesting a review or permission`() = runTest {
        val spec = tools.specifications(null).single { it.name == "ex_control_request_tab" }
        val args = JsonObject(emptyMap())
        val approval = tools.approval(automatic, spec, args)
        val result = tools.execute(automatic.copy(authorization = approval), spec.name, args)

        assertNull(approval.binding)
        assertTrue("No active tab" in result.text)
        assertTrue(result.isError)
        assertEquals(result.text, result.text)
        assertTrue(control.calls.isEmpty())
        assertTrue(control.snapshots.isEmpty())
        assertEquals(0, prompts)
    }

    @Test
    fun `unavailable history evidence returns actionable refusal without review or execution`() = runTest {
        control.snapshotFailure = IllegalStateException("History inspection is unavailable")
        val spec = tools.specifications(null).single { it.name == "ex_control_reload" }
        val args = buildJsonObject { put("tabId", "t1") }
        val approval = tools.approval(automatic, spec, args)
        val result = tools.execute(automatic.copy(authorization = approval), spec.name, args)

        assertNull(approval.binding)
        assertTrue("update the Heartbeat browser extension" in result.text)
        assertTrue(result.isError)
        assertEquals(result.text, result.text)
        assertEquals(2, control.snapshots.size)
        assertTrue(control.calls.isEmpty())
        assertEquals(0, prompts)
    }

    @Test
    fun `closing a user tab refuses without review and recommends releasing it instead`() = runTest {
        control.pageSnapshot = JsonObject(control.pageSnapshot + ("isUserTab" to JsonPrimitive(true)))
        val spec = tools.specifications(null).single { it.name == "ex_control_close_tab" }
        val args = buildJsonObject { put("tabId", "t1") }
        val approval = tools.approval(automatic, spec, args)
        val result = tools.execute(automatic.copy(authorization = approval), spec.name, args)

        assertNull(approval.binding)
        assertTrue("ex_control_release" in result.text)
        assertTrue(result.isError)
        assertEquals(result.text, result.text)
        assertTrue(control.calls.isEmpty())
        assertEquals(0, prompts)
    }

    @Test
    fun `evidence capture cancellation propagates through every automatic preparation path`() = runTest {
        control.snapshotFailure = CancellationException("Turn ended")
        val spec = tools.specifications(null).single { it.name == "ex_control_reload" }
        val args = buildJsonObject { put("tabId", "t1") }

        assertFailsWith<CancellationException> { tools.approval(automatic, spec, args) }
        assertFailsWith<CancellationException> { tools.execute(automatic, spec.name, args) }
        assertTrue(control.calls.isEmpty())
        assertEquals(0, prompts)
    }

    @Test
    fun `automatic private navigation remains reviewed even with full trust`() = runTest {
        val spec = tools.specifications(null).single { it.name == "ex_control_open" }
        val args = buildJsonObject {
            put("tabId", "t1")
            put("url", "localhost:8080")
        }
        val caller = automatic.copy(trust = TrustLevel.Full)
        val approval = tools.approval(caller, spec, args)

        assertFalse(tools.execute(caller.copy(authorization = approval), spec.name, args).isError)
        assertEquals(ExternalOperation.Open, control.calls.single().second)
        assertEquals(args, control.calls.single().third)
        assertEquals(control.pageSnapshot, control.expected.single())
        assertEquals(0, prompts)
    }

    @Test
    fun `changed page or arguments cannot execute using an earlier safe decision`() = runTest {
        val spec = tools.specifications(null).single { it.name == "ex_control_type" }
        val args = buildJsonObject {
            put("tabId", "t1")
            put("text", "Draft")
        }
        val approval = tools.approval(automatic, spec, args)
        val caller = automatic.copy(authorization = approval)
        val changed = JsonObject(args + ("text" to JsonPrimitive("Send")))
        assertTrue(tools.execute(caller, spec.name, changed).isError)
        control.pageSnapshot = buildJsonObject { put("url", "https://shop.example/checkout") }
        assertTrue(tools.execute(caller, spec.name, args).isError)
        assertTrue(control.calls.isEmpty())
        assertEquals(0, prompts)
    }

    @Test
    fun `public observations and navigation need neither a reviewer nor a page authorization`() = runTest {
        val calls = mapOf(
            "ex_control_tabs" to JsonObject(emptyMap()),
            "ex_control_active_tab" to JsonObject(emptyMap()),
            "ex_control_read" to buildJsonObject { put("tabId", "t1") },
            "ex_control_screenshot" to buildJsonObject { put("tabId", "t1") },
            "ex_control_open" to buildJsonObject {
                put("tabId", "t1")
                put("url", "https://example.com/")
            },
            "ex_control_new_tab" to buildJsonObject { put("url", "https://example.com/") },
            "ex_control_search" to buildJsonObject {
                put("tabId", "t1")
                put("query", "Kotlin documentation")
            },
        )
        for ((name, args) in calls) {
            val spec = tools.specifications(null).single { it.name == name }
            assertFalse(tools.execute(automatic, name, args).isError)
        }
        assertEquals(calls.size, control.calls.size)
        assertTrue(control.expected.all { it == null })
        assertTrue(control.snapshots.isEmpty())
        assertEquals(0, prompts)
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
        assertTrue(tools.requiresDecision(context.copy(trust = TrustLevel.Full), spec, local))
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
        val snapshots = mutableListOf<Triple<String, ExternalOperation, JsonObject>>()
        val expected = mutableListOf<JsonObject?>()
        var answer = BrowserResult("r", JsonObject(emptyMap()))
        var snapshotFailure: Exception? = null
        var pageSnapshot = buildJsonObject {
            put("tabId", "t1")
            put("url", "https://shop.example/")
            put("focused", "Search")
        }

        override suspend fun snapshot(owner: String, operation: ExternalOperation, arguments: JsonObject): JsonObject {
            snapshots += Triple(owner, operation, arguments)
            snapshotFailure?.let { throw it }
            return pageSnapshot
        }

        override suspend fun execute(
            owner: String,
            operation: ExternalOperation,
            arguments: JsonObject,
            expected: JsonObject?,
        ): BrowserResult {
            calls += Triple(owner, operation, arguments)
            this.expected += expected
            return answer
        }

        override suspend fun address(owner: String, tabId: String): String? =
            if (tabId == "t1") "https://shop.example/" else null
    }

    internal class FakeBrowser : ExternalBrowser {
        var active: ExternalTab? = null
        override val state = MutableStateFlow<ExternalBrowserState>(ExternalBrowserState.Connected("Chrome", "140"))
        override val events: Flow<ExternalTabEvent> = emptyFlow()

        val owners = mutableSetOf<String>()
        override fun isExternalSession(owner: String): Boolean = owner in owners
        override fun bindSession(owner: String, connectionId: String): AutoCloseable {
            owners += owner
            return AutoCloseable { owners -= owner }
        }

        override suspend fun readActiveTab(owner: String): JsonObject = JsonObject(emptyMap())

        override suspend fun activeTab(owner: String?): ExternalTab? = active

        override suspend fun recoverTab(owner: String, tabId: String, expectedUrl: String): ExternalTab =
            control(tabId, expectedUrl)

        override suspend fun openTab(owner: String): ExternalTab = error("Unused")

        override suspend fun control(tabId: String, expectedUrl: String): ExternalTab = error("Unused")

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
