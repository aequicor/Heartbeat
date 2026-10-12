package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.browser.api.BrowserAgentControl
import io.aequicor.heartbeat.feature.browser.api.BrowserEnabled
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineKey
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.browser.api.BrowserTab
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserAgentToolsTest {
    private val context = AgentToolContext(
        SessionRef(EngineId("pi"), SessionSourceId("local"), "trusted-session"),
        workspace = null,
        turn = TurnId("turn"),
    )

    @Test
    fun `catalog retains every tool and its trust category when either availability flag is disabled`() = runTest {
        val fixture = Fixture()
        val expected = fixture.tools.specifications(null).associate { it.name to it.action }
        assertEquals(13, expected.size)
        assertEquals("browser", fixture.tools.group)
        assertTrue(fixture.tools.title.isNotBlank())
        for ((browserEnabled, agentEnabled) in listOf(false to false, false to true, true to false, true to true)) {
            fixture.toggles.values[BrowserEnabled.key] = browserEnabled
            fixture.toggles.values[BrowserAgentControl.key] = agentEnabled
            assertEquals(expected, fixture.tools.catalog.associate { it.name to it.action })
        }
        assertEquals(0, fixture.registry.lookups)
    }

    @Test
    fun `either disabled flag hides declarations and blocks calls before machine lookup`() = runTest {
        for ((browserEnabled, agentEnabled) in listOf(false to false, false to true, true to false)) {
            val fixture = Fixture()
            fixture.toggles.values[BrowserEnabled.key] = browserEnabled
            fixture.toggles.values[BrowserAgentControl.key] = agentEnabled
            assertTrue(fixture.tools.specifications(null).isEmpty())
            val result = fixture.tools.execute(context, "browser_click", JsonObject(emptyMap()))
            assertTrue(result.isError)
            assertEquals(0, fixture.registry.lookups)
            assertTrue(fixture.machine.sent.isEmpty())
        }
    }

    @Test
    fun `public navigation and observation are read actions while every input requires command trust`() = runTest {
        val fixture = Fixture()
        assertTrue(fixture.tools.isDetachedSupported)
        val specs = fixture.tools.specifications(null).associateBy { it.name }
        for (name in listOf("browser_open", "browser_new_tab")) {
            for (url in listOf("https://example.com/page", "example.org", "http://93.184.216.34/")) {
                for (trust in TrustLevel.entries) {
                    val arguments = buildJsonObject { put("url", url) }
                    assertFalse(
                        fixture.tools.requiresDecision(context.copy(trust = trust), specs.getValue(name), arguments),
                    )
                }
            }
        }
        val search = buildJsonObject { put("query", "localhost") }
        assertFalse(fixture.tools.requiresDecision(context, specs.getValue("browser_search"), search))
        assertFalse(fixture.tools.requiresDecision(context, specs.getValue("browser_new_tab"), JsonObject(emptyMap())))
        val reads = setOf(
            "browser_tabs",
            "browser_new_tab",
            "browser_close_tab",
            "browser_open",
            "browser_search",
            "browser_read",
            "browser_screenshot",
            "browser_request_control",
        )
        val commands = setOf("browser_click", "browser_type", "browser_key", "browser_scroll", "browser_drag")
        assertEquals(reads + commands, specs.keys)
        reads.forEach { assertEquals(AgentToolAction.Read, specs.getValue(it).action, it) }
        commands.forEach { assertEquals(AgentToolAction.Command, specs.getValue(it).action, it) }
        specs.values.forEach { spec ->
            assertFalse(spec.inputSchema.getValue("properties").jsonObject.containsKey("owner"))
            assertFalse(spec.inputSchema.getValue("additionalProperties").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `navigation to local or private addresses follows the command trust row`() = runTest {
        val fixture = Fixture()
        val specs = fixture.tools.specifications(null).associateBy { it.name }
        val private = listOf(
            "http://localhost:8080/admin",
            "localhost:3000",
            "https://127.0.0.1/",
            "http://10.0.0.5/router",
            "http://192.168.1.1/",
            "http://172.16.4.2/",
            "https://printer.local/",
            "http://[::1]:8080/",
        )
        for (name in listOf("browser_open", "browser_new_tab")) {
            for (url in private) {
                val arguments = buildJsonObject { put("url", url) }
                val spec = specs.getValue(name)
                assertTrue(fixture.tools.requiresDecision(context.copy(trust = TrustLevel.Ask), spec, arguments), url)
                assertTrue(fixture.tools.requiresDecision(context.copy(trust = TrustLevel.AutoEdits), spec, arguments))
                assertFalse(fixture.tools.requiresDecision(context.copy(trust = TrustLevel.Full), spec, arguments))
                val approval = fixture.tools.approval(context, spec, arguments)
                assertTrue(approval.description.orEmpty().contains(url.substringAfter("://")), url)
            }
        }
    }

    @Test
    fun `alternative spellings of local addresses still require a decision`() = runTest {
        val fixture = Fixture()
        val specs = fixture.tools.specifications(null).associateBy { it.name }
        val spellings = listOf("0.0.0.0", "http://[::ffff:127.0.0.1]/", "LOCALHOST", "127.0.0.1.", "0x7f000001")
        for (name in listOf("browser_open", "browser_new_tab")) {
            for (url in spellings) {
                val arguments = buildJsonObject { put("url", url) }
                assertTrue(fixture.tools.requiresDecision(context, specs.getValue(name), arguments), url)
            }
        }
    }

    @Test
    fun `approvals escape invisible and direction changing characters and shorten long addresses`() = runTest {
        val fixture = Fixture()
        val specs = fixture.tools.specifications(null).associateBy { it.name }
        val hidden = "a‮b​c⁦d e f﻿g" + String(Character.toChars(0xE0041)) + "\uD800h"
        val typed = fixture.tools.approval(
            context,
            specs.getValue("browser_type"),
            buildJsonObject { put("text", hidden) },
        ).description.orEmpty()
        listOf("\\u202e", "\\u200b", "\\u2066", "\\u2028", "\\u2029", "\\ufeff", "\\u{e0041}", "\\ud800").forEach {
            assertTrue(typed.contains(it), it)
        }
        assertTrue(typed.none { Character.getType(it) == Character.FORMAT.toInt() || it.isSurrogate() })
        assertTrue(typed.contains("h"))

        val long = "http://localhost/" + "‮".repeat(1_000)
        val opened = fixture.tools.approval(
            context,
            specs.getValue("browser_open"),
            buildJsonObject { put("url", long) },
        ).description.orEmpty()
        assertTrue(opened.length < 1_000)
        assertTrue(opened.contains("показано начало"))
        assertFalse(opened.contains("‮"))
    }

    @Test
    fun `click approval shows the element as the latest read of that snapshot reported it`() = runTest {
        val fixture = Fixture()
        fixture.openTabs("tab")
        fixture.readReturns("snap")
        assertFalse(fixture.tools.execute(context, "browser_read", buildJsonObject { put("tabId", "tab") }).isError)
        val click = fixture.tools.specifications(null).single { it.name == "browser_click" }
        suspend fun describe(element: Int, snapshot: String, session: AgentToolContext = context) =
            fixture.tools.approval(
                session,
                click,
                buildJsonObject {
                    put("snapshotId", snapshot)
                    put("element", element)
                },
            ).description.orEmpty()

        val link = describe(0, "snap")
        assertTrue(link.contains("A «Pay \\u202e001»"), link)
        assertTrue(link.contains("https://bank.example/pay"))
        assertTrue(describe(1, "snap").contains("BUTTON «Cancel»"))
        assertFalse(describe(1, "snap").contains("ссылка"))
        assertTrue(describe(1, "older").contains("описание недоступно"))
        val other = context.copy(session = SessionRef(EngineId("pi"), SessionSourceId("local"), "other-session"))
        assertTrue(describe(0, "snap", other).contains("описание недоступно"))
    }

    @Test
    fun `remembered reads are kept per session, bounded and forgotten with their tab`() = runTest {
        val fixture = Fixture()
        fixture.openTabs("tab")
        fixture.readReturns("snap", label = "x".repeat(5_000))
        val click = fixture.tools.specifications(null).single { it.name == "browser_click" }
        suspend fun describe(snapshot: String) = fixture.tools.approval(
            context,
            click,
            buildJsonObject {
                put("snapshotId", snapshot)
                put("element", 0)
            },
        ).description.orEmpty()
        val read = buildJsonObject { put("tabId", "tab") }
        assertFalse(fixture.tools.execute(context, "browser_read", read).isError)
        assertTrue(describe("snap").length < 1_500)
        // Other sessions reading many times do not displace this session's latest read.
        repeat(50) { index ->
            val session = SessionRef(EngineId("pi"), SessionSourceId("local"), "session-${index % 5}")
            fixture.tools.execute(context.copy(session = session), "browser_read", read)
        }
        assertTrue(describe("snap").contains("A «x"))
        // A newer read of the same session replaces the older snapshot.
        fixture.readReturns("newer")
        fixture.tools.execute(context, "browser_read", read)
        assertTrue(describe("snap").contains("описание недоступно"))
        assertTrue(describe("newer").contains("A «Pay"))
        // Clearing browser data closes every tab, and the reads go with them.
        fixture.openTabs()
        assertTrue(describe("newer").contains("описание недоступно"))
    }

    @Test
    fun `input approvals show the tab address and exactly what is typed pressed or clicked`() = runTest {
        val fixture = Fixture()
        fixture.machine.state.value = BrowserState.Running(
            isConfigured = true,
            isEnabled = true,
            tabs = listOf(BrowserTab("tab", BrowserPage("https://bank.example/transfer"))),
        )
        val specs = fixture.tools.specifications(null).associateBy { it.name }
        suspend fun approve(name: String, vararg arguments: Pair<String, Any>): String {
            val json = buildJsonObject {
                arguments.forEach { (key, value) ->
                    if (value is Number) put(key, value) else put(key, value.toString())
                }
            }
            return fixture.tools.approval(context, specs.getValue(name), json).description.orEmpty()
        }

        val typed = approve("browser_type", "tabId" to "tab", "text" to "pay 100\nconfirm")
        assertTrue(typed.contains("pay 100\\nconfirm"))
        assertTrue(typed.contains("https://bank.example/transfer"))
        val long = approve("browser_type", "text" to "x".repeat(2_000))
        assertTrue(long.contains("2000"))
        assertTrue(long.length < 1_000)
        assertTrue(approve("browser_key", "tabId" to "tab", "key" to "Enter").contains("Enter"))
        assertTrue(approve("browser_click", "tabId" to "tab", "element" to 7).contains("7"))
        val point = approve("browser_drag", "x" to 10, "y" to 20, "toX" to 30, "toY" to 40)
        assertTrue(point.contains("10, 20"))
        assertTrue(point.contains("30, 40"))
        assertFalse(approve("browser_key", "tabId" to "missing", "key" to "Tab").contains("bank.example"))
    }

    @Test
    fun `timeout is reported as an error and cancels the pending operation`() = runTest {
        val fixture = Fixture()
        fixture.machine.completeImmediately = false
        val result = fixture.tools.execute(context, "browser_read", buildJsonObject { put("tabId", "tab") })
        assertTrue(result.isError)
        assertEquals("Browser operation timed out", result.text)
        val request = fixture.machine.requests.single()
        val cancellation = fixture.machine.sent.filterIsInstance<BrowserIntent.Public.CancelOperation>().single()
        assertEquals(request.id, cancellation.id)
    }

    @Test
    fun `unacknowledged cancellation is bounded and keeps the timeout result`() = runTest {
        val fixture = Fixture()
        fixture.machine.completeImmediately = false
        fixture.machine.acknowledgeImmediately = false
        val result = fixture.tools.execute(context, "browser_read", buildJsonObject { put("tabId", "tab") })
        assertEquals("Browser operation timed out", result.text)
    }

    @Test
    fun `refused request needs no cancellation acknowledgement`() = runTest {
        val fixture = Fixture()
        fixture.machine.performResult = SendResult.Ignored
        val result = fixture.tools.execute(context, "browser_read", buildJsonObject { put("tabId", "tab") })
        assertTrue(result.isError)
        assertTrue(fixture.machine.sent.none { it is BrowserIntent.Public.CancelOperation })
    }

    @Test
    fun `forged model identity cannot replace the session owner or request correlation`() = runTest {
        val fixture = Fixture()
        fixture.machine.completeImmediately = false
        val arguments = buildJsonObject {
            put("tabId", "tab")
            put("owner", "another-session")
            put("requestId", "forged-request")
        }
        val operation = async { fixture.tools.execute(context, "browser_read", arguments) }
        runCurrent()
        val request = fixture.machine.requests.single()
        assertEquals(Json.encodeToString(context.session), request.owner)
        assertEquals("tab", request.tabId)
        assertEquals(BrowserOperation.Read, request.operation)
        assertTrue(request.id != "forged-request")
        fixture.machine.outputs.emit(BrowserOutput.Completed(BrowserResult("forged-request", error = "wrong")))
        runCurrent()
        assertFalse(operation.isCompleted)
        fixture.machine.outputs.emit(BrowserOutput.Completed(BrowserResult(request.id)))
        assertFalse(operation.await().isError)
        assertTrue(fixture.machine.sent.none { it is BrowserIntent.Public.CancelOperation })
    }

    @Test
    fun `flag revocation after discovery still refuses a later execution`() = runTest {
        val fixture = Fixture()
        assertTrue(fixture.tools.specifications(null).isNotEmpty())
        fixture.toggles.values[BrowserAgentControl.key] = false
        val result = fixture.tools.execute(context, "browser_type", buildJsonObject { put("text", "secret") })
        assertTrue(result.isError)
        assertEquals(0, fixture.registry.lookups)
    }

    @Test
    fun `caller cancellation waits for matching native cleanup acknowledgement`() = runTest {
        val fixture = Fixture()
        fixture.machine.completeImmediately = false
        fixture.machine.acknowledgeImmediately = false
        val operation = async {
            fixture.tools.execute(context, "browser_read", buildJsonObject { put("tabId", "tab") })
        }
        runCurrent()
        val request = fixture.machine.requests.single()
        operation.cancel()
        runCurrent()
        val cancellation = fixture.machine.sent.filterIsInstance<BrowserIntent.Public.CancelOperation>().single()
        assertEquals(request.id, cancellation.id)
        assertFalse(operation.isCompleted)
        fixture.machine.outputs.emit(BrowserOutput.Cancelled("other-operation"))
        runCurrent()
        assertFalse(operation.isCompleted)
        fixture.machine.outputs.emit(BrowserOutput.Cancelled(request.id))
        operation.join()
        assertTrue(operation.isCancelled)
    }

    @Test
    fun `successful completion needs no cancellation`() = runTest {
        val fixture = Fixture()
        fixture.machine.acknowledgeImmediately = false
        assertFalse(fixture.tools.execute(context, "browser_tabs", JsonObject(emptyMap())).isError)
        assertTrue(fixture.machine.sent.none { it is BrowserIntent.Public.CancelOperation })
    }

    private class Fixture {
        val machine = ToolMachine()
        val registry = ToolRegistry(machine)
        val toggles = ToolToggles()
        val tools = BrowserAgentTools(registry, toggles)

        fun openTabs(vararg ids: String) {
            machine.state.value = BrowserState.Running(
                isConfigured = true,
                isEnabled = true,
                tabs = ids.map { BrowserTab(it, BrowserPage("https://example.com/")) },
            )
        }

        fun readReturns(snapshot: String, label: String = "Pay ‮001") {
            machine.data = { request ->
                if (request.operation == BrowserOperation.Read) {
                    buildJsonObject {
                        put("snapshotId", snapshot)
                        putJsonArray("elements") {
                            addJsonObject {
                                put("id", 0)
                                put("tag", "A")
                                put("label", label)
                                put("href", "https://bank.example/pay")
                            }
                            addJsonObject {
                                put("id", 1)
                                put("tag", "BUTTON")
                                put("label", "Cancel")
                                put("href", JsonNull)
                            }
                        }
                    }
                } else {
                    JsonObject(emptyMap())
                }
            }
        }
    }

    private class ToolMachine : MachineRef<BrowserState, BrowserIntent.Public, BrowserOutput> {
        override val name = "browser"
        override val state = MutableStateFlow<BrowserState>(BrowserState.Running(isConfigured = true, isEnabled = true))
        override val outputs = MutableSharedFlow<BrowserOutput>()
        val sent = mutableListOf<BrowserIntent.Public>()
        val requests: List<BrowserRequest>
            get() = sent.filterIsInstance<BrowserIntent.Public.Perform>().map { it.request }
        var completeImmediately = true
        var acknowledgeImmediately = true
        var performResult = SendResult.Accepted
        var data: (BrowserRequest) -> JsonObject = { JsonObject(emptyMap()) }

        override suspend fun send(intent: BrowserIntent.Public): SendResult {
            sent += intent
            when (intent) {
                is BrowserIntent.Public.Perform -> if (performResult != SendResult.Accepted) {
                    return performResult
                } else if (completeImmediately) {
                    outputs.emit(BrowserOutput.Completed(BrowserResult(intent.request.id, data(intent.request))))
                }

                is BrowserIntent.Public.CancelOperation -> if (acknowledgeImmediately) {
                    outputs.emit(BrowserOutput.Cancelled(intent.id))
                }

                else -> error("Unexpected browser intent")
            }
            return SendResult.Accepted
        }
    }

    private class ToolRegistry(private val machine: ToolMachine) : MachineRegistry {
        var lookups = 0

        @Suppress("UNCHECKED_CAST") // Only BrowserMachineKey is registered in this fixture.
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? {
            lookups++
            check(key == BrowserMachineKey)
            return machine as MachineRef<S, P, O>
        }

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult = requireNotNull(find(key)).send(intent)
    }

    private class ToolToggles : FeatureToggles {
        val values = mutableMapOf(BrowserEnabled.key to true, BrowserAgentControl.key to true)

        @Suppress("UNCHECKED_CAST") // The tools only read Boolean feature flags.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = (values[toggle.key] ?: toggle.default) as T

        @Suppress("UNCHECKED_CAST") // The fixture only declares Boolean feature flags.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(
            (values[toggle.key] ?: toggle.default) as T,
        )
    }
}
