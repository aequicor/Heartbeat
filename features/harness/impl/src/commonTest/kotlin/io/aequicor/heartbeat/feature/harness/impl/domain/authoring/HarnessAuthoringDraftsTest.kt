package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.ItemId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HarnessAuthoringDraftsTest {
    @Test
    fun `create defaults to an attached harness connected to the chat`() {
        val parsed = assertIs<Parsed.Valid<AuthoringCommand.Create>>(
            parseCreate(args("name" to "compose_ui", "title" to "Compose UI")),
        ).value
        assertEquals("compose_ui", parsed.name.value)
        assertTrue(parsed.isAttached)
    }

    @Test
    fun `create refuses bad slugs multi-line titles and unknown arguments without echoing them`() {
        listOf(
            args("name" to "X", "title" to "T"),
            args("name" to "ok_name", "title" to "line\nbreak"),
            args("name" to "ok_name", "title" to "T", "extra" to "secret-ish"),
            args("name" to "ok_name", "title" to "T", "scope" to "galaxy"),
            args("name" to "ok_name", "title" to "T", "scope" to ""),
            buildJsonObject {
                put("name", "ok_name")
                put("title", "T")
                put("scope", true)
            },
        ).forEach { arguments ->
            val invalid = assertIs<Parsed.Invalid>(parseCreate(arguments))
            assertFalse(invalid.message.contains("secret-ish"))
        }
        assertIs<Parsed.Valid<AuthoringCommand.Create>>(
            parseCreate(args("name" to "ok_name", "title" to "T", "scope" to "attached")),
        )
    }

    @Test
    fun `update requires at least one change`() {
        assertIs<Parsed.Invalid>(parseUpdate(args("harness" to "compose_ui")))
        assertIs<Parsed.Invalid>(parseUpdate(args("harness" to "compose_ui", "scope" to "attached")))
        val update = assertIs<Parsed.Valid<AuthoringCommand.Update>>(
            parseUpdate(args("harness" to "compose_ui", "description" to "")),
        ).value
        assertEquals("", update.description)
    }

    @Test
    fun `items keep exact content and derive template arguments from placeholders`() {
        val template = assertIs<Parsed.Valid<AuthoringCommand.PutItem>>(
            parseItemPut(
                args(
                    "harness" to "compose_ui",
                    "kind" to "template",
                    "name" to "step",
                    "description" to "Design step",
                    "content" to "Design {{screen}} for {{platform}}\n",
                ),
            ),
        ).value.item
        assertEquals(setOf("platform", "screen"), template.arguments)
        val item = assertIs<HarnessItem.Template>(template.toItem(ItemId("id"), isEnabled = false))
        assertEquals("Design {{screen}} for {{platform}}\n", item.body)
        assertFalse(item.isEnabled)
    }

    @Test
    fun `skills and templates need a description instructions respect their limit`() {
        assertIs<Parsed.Invalid>(
            parseItemPut(args("harness" to "h1", "kind" to "skill", "name" to "s", "content" to "Body")),
        )
        assertIs<Parsed.Invalid>(
            parseItemPut(
                args(
                    "harness" to "h1",
                    "kind" to "instruction",
                    "name" to "rule",
                    "content" to "x".repeat(HarnessLimits.INSTRUCTION_CHARS + 1),
                ),
            ),
        )
    }

    @Test
    fun `code parsing does not guess effects from comments strings or credential shaped examples`() {
        for (source in listOf(
            "// never use println or System.exit\nhooks.toString()",
            "val label = \"👩‍💻 / می‌روم\"",
            "val example = \"sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789\"",
            "val description = \"runBlocking is explained in this lesson\"",
        )) {
            assertIs<Parsed.Valid<AuthoringCommand.PutItem>>(
                parseItemPut(args("harness" to "h1", "kind" to "script", "name" to "verify", "content" to source)),
            )
        }
    }

    @Test
    fun `workflow input must be a supported schema and is only accepted for workflows`() {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("screen") { put("type", "string") } }
            put("required", JsonArray(listOf(JsonPrimitive("screen"))))
        }
        val workflow = buildJsonObject {
            put("harness", "h1")
            put("kind", "workflow")
            put("name", "screen")
            put("content", "workflow { input -> input }")
            put("input", schema)
        }
        val draft = assertIs<Parsed.Valid<AuthoringCommand.PutItem>>(parseItemPut(workflow)).value.item
        assertEquals(schema, draft.input)
        val skill = JsonObject(workflow + ("kind" to JsonPrimitive("skill")) + ("description" to JsonPrimitive("d")))
        assertIs<Parsed.Invalid>(parseItemPut(skill))
        val unsupported = JsonObject(workflow + ("input" to buildJsonObject { put("type", "array") }))
        assertIs<Parsed.Invalid>(parseItemPut(unsupported))
    }
    private fun args(vararg values: Pair<String, String>): JsonObject =
        JsonObject(values.associate { (key, value) -> key to JsonPrimitive(value) })
}
