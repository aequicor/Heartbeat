package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class HarnessModelTest {
    @Test
    fun `all item and scope variants round trip without exposing source in diagnostics`() {
        val id = ItemId("item")
        val name = ItemName("item")
        val secret = "private content"
        val items = listOf(
            HarnessItem.Skill(id, name, secret, secret),
            HarnessItem.Instruction(id, name, secret),
            HarnessItem.Template(id, name, secret, secret, setOf("arg")),
            HarnessItem.Script(id, name, secret, secret),
            HarnessItem.Workflow(id, name, secret, secret, schema("""{"type":"object"}""")),
        )
        val scopes = listOf(HarnessScope.Attached, HarnessScope.Profile, HarnessScope.Projects(setOf(Project)))
        for (item in items) {
            for (scope in scopes) {
                val harness = harness("sample").copy(items = listOf(item), scope = scope)
                assertEquals(harness, Json.decodeFromString<Harness>(Json.encodeToString(harness)))
                assertFalse(harness.toString().contains(secret))
                assertFalse(item.toString().contains(secret))
            }
        }
        for (approval in HarnessApproval.entries) {
            assertEquals(approval, Json.decodeFromString<HarnessApproval>(Json.encodeToString(approval)))
        }
    }

    @Test
    fun `names empty project sets duplicate items and oversized content fail validation`() {
        for (name in listOf("a", "Uppercase", "has-dash", "has space", "a".repeat(21))) {
            assertFailsWith<IllegalArgumentException> { HarnessName(name) }
        }
        assertEquals("a", ItemName("a").value)
        assertFailsWith<IllegalArgumentException> { ItemName("a".repeat(33)) }
        assertFailsWith<IllegalArgumentException> { HarnessScope.Projects(emptySet()) }
        val item = HarnessItem.Instruction(ItemId("id"), ItemName("name"), "text")
        assertFailsWith<IllegalArgumentException> { harness("sample").copy(items = listOf(item, item)) }
        assertFailsWith<IllegalArgumentException> {
            item.copy(text = "x".repeat(HarnessLimits.INSTRUCTION_CHARS + 1))
        }
        assertFailsWith<IllegalArgumentException> { harness("sample").copy(revision = -1) }
    }

    @Test
    fun `storage quotas count serialized utf8 bytes and the entire profile`() {
        val small = harness("small")
        assertTrue(isHarnessLibraryWithinLimits(listOf(small)))
        assertFalse(isHarnessLibraryWithinLimits(listOf(small, small.copy(id = HarnessId("other")))))
        val oversized = small.copy(description = "я".repeat(HarnessLimits.BYTES_PER_HARNESS / 2))
        assertTrue(oversized.storageBytes() > HarnessLimits.BYTES_PER_HARNESS)
        assertFalse(isHarnessLibraryWithinLimits(listOf(oversized)))
        val many = (1..10).map { harness("item_$it").copy(description = "x".repeat(220_000)) }
        assertTrue(many.all { it.storageBytes() <= HarnessLimits.BYTES_PER_HARNESS })
        assertFalse(isHarnessLibraryWithinLimits(many))
    }

    @Test
    fun `activation unions scopes attachments and helper affiliation with deterministic overflow`() {
        val scoped = harness("project").copy(scope = HarnessScope.Projects(setOf(Project)))
        val attached = harness("attached").copy(scope = HarnessScope.Attached)
        val helper = harness("helper").copy(scope = HarnessScope.Attached)
        val disabled = harness("disabled").copy(isEnabled = false)
        val attachments = mapOf(Session to setOf(attached.id, disabled.id))
        val library = listOf(scoped, attached, helper, disabled)
        assertEquals(
            listOf(attached, helper, scoped),
            activeHarnesses(library, attachments, Session, Project, helper.id),
        )
        assertEquals(emptyList(), activeHarnesses(library, emptyMap(), Session, null))
        assertEquals(emptyList(), activeHarnesses(library, attachments, Session, Project, isSuspended = true))
        val crowded = (0..9).map { harness("item_$it") }.reversed()
        assertEquals(
            (0..7).map { "item_$it" },
            activeHarnesses(crowded, emptyMap(), Session, null).map { it.name.value },
        )
        assertFalse(HarnessEnabled.default)
    }

    @Test
    fun `workflow input schemas accept only the supported typed subset`() {
        assertTrue(isHarnessInputSchema(schema("{}")))
        assertTrue(
            isHarnessInputSchema(
                schema(
                    """{
            "type":"object","properties":{
                "title":{"type":"string","enum":["small","large"]},
                "size":{"type":"number"},"flag":{"type":"boolean"},
                "nested":{"type":"object","properties":{}}
            },"required":["title"]
        }""",
                ),
            ),
        )
        for (invalid in listOf(
            """{"type":"array"}""",
            """{"type":"object","required":["missing"]}""",
            """{"type":"object","additionalProperties":false}""",
            """{"type":"object","properties":{"size":{"type":"number","enum":["1"]}}}""",
            """{"type":"object","properties":{"flag":{"type":"boolean","enum":[true,true]}}}""",
        )) {
            assertFalse(isHarnessInputSchema(schema(invalid)), invalid)
        }
    }

    private fun schema(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun harness(name: String) = Harness(
        HarnessId(name), HarnessName(name), "Title", "Description", HarnessScope.Profile, true,
        emptyList(), ToolPolicySpec(setOf("run_command"), mapOf("pi" to mapOf("read" to ToolSwitch.Off))),
        Session, 0, Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0),
    )

    private companion object {
        val Project = WorkspaceRef("project")
        val Session = SessionRef(EngineId("pi"), SessionSourceId("profile"), "native")
    }
}
