package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.harness.api.HarnessApproval
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
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
    fun `code with forbidden constructs secrets or hidden characters is refused before any question`() {
        listOf(
            "kotlin.system.exitProcess(0)",
            "System . exit(1)",
            "@file:Suppress(\"x\")\nval a = 1",
            "print" + "ln(\"debug\")",
            "val key = \"sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789\"",
            "val a = 1‮",
        ).forEach { source ->
            assertIs<Parsed.Invalid>(
                parseItemPut(args("harness" to "h1", "kind" to "script", "name" to "verify", "content" to source)),
                source,
            )
        }
        assertIs<Parsed.Valid<AuthoringCommand.PutItem>>(
            parseItemPut(
                args("harness" to "h1", "kind" to "script", "name" to "verify", "content" to "hooks.toString()"),
            ),
        )
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

    @Test
    fun `tool policy strips MCP prefixes and reads on and off switches`() {
        val arguments = buildJsonObject {
            put("harness", "h1")
            put("hosted_off", JsonArray(listOf(JsonPrimitive("mcp__heartbeat_tools__web_search"))))
            putJsonObject("native") {
                putJsonObject("claude") {
                    put("Bash", "on")
                    put("WebFetch", "off")
                }
            }
        }
        val parsed = assertIs<Parsed.Valid<AuthoringCommand.SetTools>>(parseToolsSet(arguments)).value
        assertEquals(setOf("web_search"), parsed.hostedOff)
        assertEquals(mapOf("Bash" to ToolSwitch.On, "WebFetch" to ToolSwitch.Off), parsed.native["claude"])
        val wrong = buildJsonObject {
            put("harness", "h1")
            putJsonObject("native") { putJsonObject("claude") { put("Bash", "maybe") } }
        }
        assertIs<Parsed.Invalid>(parseToolsSet(wrong))
    }

    @Test
    fun `approval level decides only for texts while code and native enabling always ask`() {
        HarnessApproval.entries.forEach { level ->
            assertFalse(level.requiresDecision(TrustLevel.Full, isAlwaysAsked = false))
        }
        listOf(TrustLevel.Ask, TrustLevel.AutoEdits).forEach { trust ->
            assertTrue(HarnessApproval.Ask.requiresDecision(trust, isAlwaysAsked = false))
            assertTrue(HarnessApproval.ByTrust.requiresDecision(trust, isAlwaysAsked = false))
            assertFalse(HarnessApproval.AcceptAll.requiresDecision(trust, isAlwaysAsked = false))
        }
        TrustLevel.entries.forEach { trust ->
            HarnessApproval.entries.forEach { level -> assertTrue(level.requiresDecision(trust, isAlwaysAsked = true)) }
        }
    }

    @Test
    fun `policy diff names every change and detects tools switched on or given back`() {
        val current = ToolPolicySpec(setOf("web_fetch"), mapOf("claude" to mapOf("Bash" to ToolSwitch.On)))
        val next = ToolPolicySpec(
            setOf("web_search"),
            mapOf(
                "claude" to mapOf("Bash" to ToolSwitch.On, "Write" to ToolSwitch.On),
                "pi" to mapOf("read" to ToolSwitch.Off),
            ),
        )
        assertEquals(listOf("web_fetch", "claude/Write"), reEnabled(current, next))
        assertEquals(
            listOf(
                "Heartbeat tool web_search: off",
                "Heartbeat tool web_fetch: default",
                "claude tool Write: on (was default)",
                "pi tool read: off (was default)",
            ),
            policyDiff(current, next),
        )
        // Back to current: web_search leaves hosted_off and pi read leaves Off; dropping On restricts only.
        assertEquals(listOf("web_search", "pi/read"), reEnabled(next, current))
    }

    @Test
    fun `releasing an off switch counts as enabling whatever the default`() {
        val off = ToolPolicySpec(native = mapOf("codex" to mapOf("shell" to ToolSwitch.Off)))
        assertEquals(listOf("codex/shell"), reEnabled(off, ToolPolicySpec()))
        val on = ToolPolicySpec(native = mapOf("codex" to mapOf("shell" to ToolSwitch.On)))
        assertEquals(listOf("codex/shell"), reEnabled(off, on))
        assertTrue(reEnabled(ToolPolicySpec(), off).isEmpty())
        assertTrue(reEnabled(on, off).isEmpty())
        assertTrue(off.hasRestrictions && !off.hasNativeOn)
        assertTrue(on.hasNativeOn && !on.hasRestrictions)
        assertTrue(ToolPolicySpec(hostedOff = setOf("web_search")).hasRestrictions)
    }

    private fun args(vararg values: Pair<String, String>): JsonObject =
        JsonObject(values.associate { (key, value) -> key to JsonPrimitive(value) })
}
