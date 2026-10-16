package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.HarnessContentTools
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessToggles
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HARNESS_CONTEXT_USAGE
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessContentToolsTest {
    private val toggles = SchedulerAccessToggles()
    private val context = AgentToolContext(dispatchSession, null, TurnId("turn"))
    private val instruction = HarnessItem.Instruction(ItemId("note"), ItemName("note"), "Private instruction")
    private val skill = HarnessItem.Skill(ItemId("skill"), ItemName("skill"), "Load when needed", "Private body")
    private val template = HarnessItem.Template(
        ItemId("template"),
        ItemName("template"),
        "Greeting",
        "Hello {{name}}",
        setOf("name"),
    )
    private var active = listOf(harness.copy(items = listOf(instruction, skill, template)))
    private var reads = 0
    private val tools = HarnessContentTools(
        toggles,
        lazy {
            HarnessActiveAccess { _, session ->
                assertEquals(dispatchSession, session)
                reads++
                active
            }
        },
    )
    private val scope = AgentToolScope(null, session = dispatchSession)

    @Test
    fun `automatic instructions remain available without tools and skill bodies stay on demand`() = runTest {
        val automatic = scope.copy(isRefreshedPerTurn = true, declared = emptySet())
        val first = tools.instructions(automatic)
        assertTrue(first.contains(instruction.text))
        assertTrue(first.contains(HARNESS_CONTEXT_USAGE))
        assertTrue(first.contains(skill.description))
        assertFalse(first.contains(skill.body))
        assertFalse(first.contains(HarnessTools.CONTEXT))
        assertFalse(first.contains(HarnessTools.SKILL_LOAD))
        assertEquals(first, tools.instructions(automatic))
        val updated = instruction.copy(text = "Updated instruction")
        active = listOf(harness.copy(items = listOf(updated, skill)))
        val refreshed = tools.instructions(automatic)
        assertTrue(refreshed.contains(updated.text))
        assertFalse(refreshed.contains(instruction.text))
    }

    @Test
    fun `frozen and refreshed guidance reserve context reads for recovery`() = runTest {
        listOf(false, true).forEach { refreshed ->
            val guide = tools.instructions(scope.copy(isRefreshedPerTurn = refreshed))
            assertTrue(guide.contains(HARNESS_CONTEXT_USAGE))
            assertTrue(guide.contains("harness_context только для восстановления"))
            assertTrue(guide.contains("Скиллы загружай по необходимости"))
            assertTrue(guide.contains("только если нужного содержимого ещё нет в контексте"))
        }
    }

    @Test
    fun `frozen instructions stay stable while refreshed instructions use the latest committed text`() = runTest {
        val frozen = tools.instructions(scope)
        assertEquals(0, reads)
        assertFalse(frozen.contains(instruction.text))
        assertTrue(tools.instructions(scope.copy(isRefreshedPerTurn = true)).contains(instruction.text))
        active = emptyList()
        assertEquals(frozen, tools.instructions(scope))
        assertFalse(tools.instructions(scope.copy(isRefreshedPerTurn = true)).contains(instruction.text))
    }

    @Test
    fun `declarations stay fixed but execution immediately loses disabled content`() = runTest {
        val declarations = tools.specifications(scope)
        val args = buildJsonObject { put("name", "skill") }
        assertEquals(skill.body, tools.execute(context, HarnessTools.SKILL_LOAD, args).text)
        active = emptyList()
        assertEquals(declarations, tools.specifications(scope))
        assertTrue(tools.execute(context, HarnessTools.SKILL_LOAD, args).isError)
        toggles.enabled.value = false
        reads = 0
        assertTrue(tools.specifications(scope).isEmpty())
        assertEquals("", tools.instructions(scope))
        assertTrue(tools.execute(context, HarnessTools.SKILL_LOAD, args).isError)
        assertEquals(0, reads)
    }

    @Test
    fun `templates substitute strings literally and reject extra or nonstring arguments`() = runTest {
        val args = buildJsonObject {
            put("name", "test/template")
            putJsonObject("args") { put("name", "{{literal}}") }
        }
        assertEquals("Hello {{literal}}", tools.execute(context, HarnessTools.PROMPT_GET, args).text)
        val invalid = buildJsonObject {
            put("name", "template")
            putJsonObject("args") { put("name", 12) }
        }
        assertTrue(tools.execute(context, HarnessTools.PROMPT_GET, invalid).isError)
        val extra = buildJsonObject {
            put("name", "skill")
            put("session", "foreign")
        }
        assertTrue(tools.execute(context, HarnessTools.SKILL_LOAD, extra).isError)
    }

    @Test
    fun `explicit context includes all instructions while system guidance respects declared tools`() = runTest {
        active = listOf(
            harness.copy(
                items = (0..8).map {
                    instruction.copy(id = ItemId("n$it"), name = ItemName("n$it"), text = "$it".repeat(2_000))
                },
            ),
        )
        val limited = scope.copy(isRefreshedPerTurn = true, declared = setOf(HarnessTools.SKILL_LOAD))
        val system = tools.instructions(limited)
        assertTrue(system.length <= 8192)
        assertFalse(system.contains(HarnessTools.CONTEXT))
        assertFalse(system.contains(HarnessTools.PROMPT_GET))
        assertTrue(system.contains(HarnessTools.SKILL_LOAD))
        val full = tools.execute(context, HarnessTools.CONTEXT, JsonObject(emptyMap()))
        assertTrue(full.text.contains("8".repeat(2_000)))
        assertFalse(full.isError)
    }

    @Test
    fun `failed library activation leaves hosted instructions and content closed but intact`() = runTest {
        val failing = HarnessContentTools(toggles, lazy { HarnessActiveAccess { _, _ -> error("unavailable") } })
        assertEquals("", failing.instructions(scope.copy(isRefreshedPerTurn = true)))
        assertTrue(failing.instructions(scope).isNotEmpty())
        val args = buildJsonObject { put("name", "skill") }
        assertTrue(failing.execute(context, HarnessTools.SKILL_LOAD, args).isError)
    }
}
