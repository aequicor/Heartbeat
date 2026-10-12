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
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.browser.api.BrowserAgentControl
import io.aequicor.heartbeat.feature.browser.api.BrowserEnabled
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineKey
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    fun `navigation and observation are read actions while every input requires command trust`() = runTest {
        val fixture = Fixture()
        assertTrue(fixture.tools.isDetachedSupported)
        val specs = fixture.tools.specifications(null).associateBy { it.name }
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
        val cancellation = fixture.machine.sent.filterIsInstance<BrowserIntent.Public.CancelOperation>().single()
        assertEquals(request.id, cancellation.id)
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
    fun `successful completion also waits for cleanup acknowledgement`() = runTest {
        val fixture = Fixture()
        fixture.machine.acknowledgeImmediately = false
        val operation = async { fixture.tools.execute(context, "browser_tabs", JsonObject(emptyMap())) }
        runCurrent()
        val request = fixture.machine.requests.single()
        assertFalse(operation.isCompleted)
        fixture.machine.outputs.emit(BrowserOutput.Cancelled(request.id))
        assertFalse(operation.await().isError)
    }

    private class Fixture {
        val machine = ToolMachine()
        val registry = ToolRegistry(machine)
        val toggles = ToolToggles()
        val tools = BrowserAgentTools(registry, toggles)
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

        override suspend fun send(intent: BrowserIntent.Public): SendResult {
            sent += intent
            when (intent) {
                is BrowserIntent.Public.Perform -> if (completeImmediately) {
                    outputs.emit(BrowserOutput.Completed(BrowserResult(intent.request.id)))
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
