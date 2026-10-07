package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessRegistrationOwnershipTest {
    @Test
    fun `declarations copy input and reject registration after publication`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val mutable = mutableMapOf<String, JsonElement>("type" to JsonPrimitive("object"))
        fixture.evaluate = {
            it.agent.tool(ItemName("tool"), "Tool", JsonObject(mutable)) { ScriptToolResult("") }
            mutable["type"] = JsonPrimitive("integer")
        }
        val instance = fixture.activate()
        val registrations = assertIs<HarnessScriptContext>(instance.runtimeContext).registrations
        assertEquals(JsonPrimitive("object"), registrations.tools().single().specification.inputSchema["type"])
        assertFailsWith<IllegalStateException> {
            fixture.scripts.single().agent.tool(
                ItemName("late"),
                "Late",
                JsonObject(emptyMap()),
            ) { ScriptToolResult("") }
        }
    }

    @Test
    fun `registration captures origin and disposal only refuses future acquisition`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.origins.origin = HarnessCallOrigin(isHookRestricted = true)
        var registration: ScriptRegistration? = null
        fixture.evaluate = { registration = it.agent.instructions { "note" } }
        val instance = fixture.activate()
        val callback = assertIs<HarnessScriptContext>(instance.runtimeContext).registrations.instructions().single()
        fixture.origins.origin = HarnessCallOrigin()
        assertTrue(callback.origin.isHookRestricted)
        val acquired = assertNotNull(callback.acquire())
        assertNotNull(registration).dispose()
        assertNull(callback.acquire())
        assertFalse(callback.isActive)
        // The prior callback reference remains owned by its admitted invocation, not by registration visibility.
        assertEquals("note", acquired(AgentToolScope(workspace = null)))
    }

    @Test
    fun `retirement closes registrations and retained scope cannot register again`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.evaluate = { it.agent.instructions { "note" } }
        val instance = fixture.activate()
        val registrations = assertIs<HarnessScriptContext>(instance.runtimeContext).registrations
        val callback = registrations.instructions().single()
        fixture.runtime.deactivate(HarnessEffect.Deactivate(listOf(instance.request), false, generation = 1))
        runCurrent()
        assertFalse(callback.isActive)
        assertTrue(registrations.instructions().isEmpty())
        assertFailsWith<IllegalStateException> { fixture.scripts.single().hooks.beforePrompt { _, _ -> null } }
    }

    @Test
    fun `repeated disposal is compacted before the next registration`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val instance = fixture.activate()
        val script = fixture.scripts.single()
        repeat(100) { script.agent.instructions { "private" }.dispose() }
        script.agent.instructions { "current" }
        val entries = assertIs<HarnessScriptContext>(instance.runtimeContext).registrations.instructions()
        assertEquals(1, entries.size)
        assertEquals(
            "current",
            assertNotNull(entries.single().acquire())(
                AgentToolScope(workspace = null),
            ),
        )
    }

    @Test
    fun `published snapshot orders owners and never exposes staged candidate`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.desired = scriptRequest(1, owner = "zulu")
        fixture.activate()
        fixture.desired = scriptRequest(2, owner = "alpha")
        fixture.evaluate = {
            assertEquals(
                listOf("zulu"),
                fixture.runtime.published().map { it.request.harness.name.value },
            )
        }
        fixture.activate()
        assertEquals(listOf("alpha", "zulu"), fixture.runtime.published().map { it.request.harness.name.value })
    }
}
