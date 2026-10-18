package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiSpecCodecTest {
    @Test
    fun `the pull request map decodes with every part`() {
        val spec = assertIs<UiSpecDecoding.Decoded>(decodeUiSpec(PrMapSpec)).spec

        assertEquals("PR map", spec.title)
        assertEquals(JsonPrimitive("open"), spec.state["filter"])
        assertEquals(UiDataSource("prs", args(PR_MAP_ARGS)), spec.data["prs"])
        assertEquals(setOf("details"), spec.modals.keys)
        val column = assertIs<UiNode.Column>(spec.body)
        val row = assertIs<UiNode.Row>(column.children[0])
        assertEquals(UiOption("all", "All"), assertIs<UiNode.Select>(row.children[0]).options[1])
        val refresh = assertIs<UiNode.Button>(row.children[1])
        assertEquals(UiButtonStyle.Primary, refresh.style)
        assertEquals(UiAction.Reload("prs"), refresh.action)
        val branch = assertIs<UiNode.If>(column.children[1])
        assertEquals("{{status.prs.loading}}", branch.condition)
        val table = assertIs<UiNode.Table>(branch.otherwise)
        assertEquals(listOf(0.5f, 3f), table.columns.map { it.weight })
        assertEquals("details", assertIs<UiAction.Open>(table.onRowClick).modal)
        assertEquals(UiNode.Diagram("{{data.prs.plantuml}}"), column.children[2])
        val steps = assertIs<UiAction.Steps>(assertIs<UiNode.Button>(column.children[3]).action)
        assertEquals(listOf(UiAction.Prompt("Prioritize {{data.prs.numbers}}", true), UiAction.Close), steps.actions)
    }

    @Test
    fun `every node and action type decodes`() {
        val spec = assertIs<UiSpecDecoding.Decoded>(decodeUiSpec(EveryType)).spec

        val types = assertIs<UiNode.Column>(spec.body).children.map { it::class.simpleName }
        assertEquals(
            listOf(
                "Divider", "Spacer", "Text", "Markdown", "Badge", "Progress", "KeyValue", "Empty", "Card", "Section",
                "Items", "Toggle", "TextField", "Native", "Web", "Button",
            ),
            types,
        )
        val button = assertIs<UiNode.Button>(spec.body.children.last())
        val actions = assertIs<UiAction.Steps>(button.action).actions.map { it::class.simpleName }
        assertEquals(
            listOf("Call", "SetState", "Open", "OpenUrl", "Copy", "SaveFile", "Reveal", "Prompt", "Reload", "Close"),
            actions,
        )
    }

    @Test
    fun `web height presets decode and unknown dimensions are rejected`() {
        for (height in UiWebHeight.entries) {
            val text = """{"version":1,"body":{"type":"web","html":"<p>App</p>",
                "height":"${height.name.lowercase()}"}}"""
            val spec = assertIs<UiSpecDecoding.Decoded>(decodeUiSpec(text)).spec
            assertEquals(height, assertIs<UiNode.Web>(spec.body).height)
        }
        assertIs<UiSpecDecoding.Rejected>(
            decodeUiSpec("""{"version":1,"body":{"type":"web","html":"<p>App</p>","height":"fullscreen"}}"""),
        )
    }

    @Test
    fun `focus card decodes with an owner local surface name`() {
        val text = """{"version":1,"body":{"type":"button","label":"Continue",
            "action":{"type":"focusCard","surface":"workout"}}}"""
        val spec = assertIs<UiSpecDecoding.Decoded>(decodeUiSpec(text)).spec
        assertEquals(UiAction.FocusCard("workout"), assertIs<UiNode.Button>(spec.body).action)
    }

    @Test
    fun `specs without quick actions decode with none and never gain the field`() {
        val spec = assertIs<UiSpecDecoding.Decoded>(decodeUiSpec(PrMapSpec)).spec

        assertEquals(emptyList(), spec.quickActions)
        assertFalse("quickActions" in PrMapSpec)
        val stored = assertNotNull(formatUiSpec(PrMapSpec, isIndented = false))
        assertFalse("quickActions" in stored)
        assertEquals(spec, assertIs<UiSpecDecoding.Decoded>(decodeUiSpec(stored)).spec)
    }

    @Test
    fun `quick actions decode with every field and default to a secondary visible enabled button`() {
        val text = """{"version":1,"state":{"stage":"plan"},"data":{"s":{"method":"snapshot"}},
            "quickActions":[
              {"id":"next","label":"Next {{state.stage}}","style":"primary","icon":"play",
               "visibleWhen":"{{data.s.hasNext}}","enabledWhen":"{{status.s.ready}}","confirm":"Go on?",
               "action":{"type":"sequence","actions":[
                 {"type":"call","method":"advance","args":{"stage":"{{state.stage}}"},"into":"stage"},
                 {"type":"reload","source":"s"}]}},
              {"id":"plain","label":"Plain","action":{"type":"set","key":"stage","value":"plan"}}],
            "body":{"type":"divider"}}"""
        val spec = assertIs<UiSpecDecoding.Decoded>(decodeUiSpec(text)).spec

        val next = spec.quickActions[0]
        assertEquals("next", next.id)
        assertEquals(UiButtonStyle.Primary, next.style)
        assertEquals("play", next.icon)
        assertEquals("{{data.s.hasNext}}", next.visibleWhen)
        assertEquals("{{status.s.ready}}", next.enabledWhen)
        assertEquals("Go on?", next.confirm)
        assertEquals(
            listOf("Call", "Reload"),
            assertIs<UiAction.Steps>(next.action).actions.map { it::class.simpleName },
        )
        val plain = spec.quickActions[1]
        assertEquals(UiButtonStyle.Secondary, plain.style)
        assertNull(plain.icon)
        assertNull(plain.visibleWhen)
        assertNull(plain.enabledWhen)
        assertNull(plain.confirm)
        assertEquals("UiSpec(version=1, state=1, data=1, modals=0, quickActions=2)", spec.toString())
    }

    @Test
    fun `quick action structure is strict`() {
        val body = """"body":{"type":"divider"}"""
        for (quick in listOf(
            """{"id":"a","label":"A"}""",
            """{"label":"A","action":{"type":"close"}}""",
            """{"id":"a","action":{"type":"close"}}""",
            """{"id":"a","label":"A","action":{"type":"close"},"colour":"red"}""",
            """{"id":"a","label":"A","style":"loud","action":{"type":"close"}}""",
        )) {
            val error = malformed("""{"version":1,"quickActions":[$quick],$body}""")
            assertTrue(error.path.startsWith("$.quickActions[0]"), error.describe())
        }
        malformed("""{"version":1,"quickActions":{"id":"a"},$body}""")
    }

    @Test
    fun `structure errors name the place and the cause`() {
        val unknownType = malformed("""{"version":1,"body":{"type":"colum","children":[]}}""")
        assertTrue("colum" in unknownType.detail.orEmpty(), unknownType.describe())

        val unknownKey = malformed("""{"version":1,"body":{"type":"text","text":"x","colour":"red"}}""")
        assertTrue("colour" in unknownKey.detail.orEmpty(), unknownKey.describe())
        assertEquals("$.body", unknownKey.path, unknownKey.describe())

        val missing = malformed("""{"version":1,"body":{"type":"button","label":"Go"}}""")
        assertTrue("action" in missing.detail.orEmpty(), missing.describe())

        val wrongValue = malformed("""{"version":1,"body":{"type":"text","text":"x","tone":"loud"}}""")
        assertTrue("loud" in wrongValue.detail.orEmpty(), wrongValue.describe())

        malformed("""{"version":1}""")
        malformed("""{"version":"one","body":{"type":"divider"}}""")
    }

    @Test
    fun `gap errors identify the nested card regardless of discriminator position`() {
        for (card in listOf(
            """{"type":"card","gap":"small","children":[]}""",
            """{"children":[],"gap":"small","type":"card"}""",
        )) {
            val error = malformed("""{"version":1,"body":{"children":[$card],"type":"column"}}""")
            assertEquals("$.body.children[0]", error.path, error.describe())
            assertTrue("gap" in error.detail.orEmpty(), error.describe())
        }
    }

    @Test
    fun `layout gaps are accepted and preserve arbitrary state and argument objects`() {
        val spec = assertIs<UiSpecDecoding.Decoded>(
            decodeUiSpec(
                """{"version":1,"state":{"payload":{"gap":3,"type":"custom"}},
                    "body":{"children":[{"gap":"small","children":[],"type":"row"}],
                    "gap":"large","type":"column"},
                    "data":{"source":{"method":"load","args":{"gap":7,"type":"request"}}}}""",
            ),
        ).spec
        assertEquals(UiGap.Large, assertIs<UiNode.Column>(spec.body).gap)
        assertEquals(UiGap.Small, assertIs<UiNode.Row>(spec.body.children.single()).gap)
        assertEquals(args("""{"gap":3,"type":"custom"}"""), spec.state["payload"])
        assertEquals(args("""{"gap":7,"type":"request"}"""), spec.data.getValue("source").args)
    }

    @Test
    fun `nested action errors retain modal and sequence paths`() {
        val error = malformed(
            """{"version":1,"body":{"type":"divider"},"modals":{"details":{"title":"Details",
                "body":{"label":"Copy","action":{"actions":[{"text":"x","extra":true,"type":"copy"}],
                "type":"sequence"},"type":"button"}}}}""",
        )
        assertEquals("$.modals['details'].body.action.actions[0]", error.path, error.describe())
        assertTrue("extra" in error.detail.orEmpty(), error.describe())
    }

    @Test
    fun `invalid json size and version are rejected`() {
        assertEquals(UiSpecProblem.InvalidJson, single("""{"version":1,"""").problem)
        assertEquals(UiSpecProblem.InvalidJson, single("").problem)
        val padding = " ".repeat(ServiceUiLimits.SPEC_BYTES)
        assertEquals(UiSpecProblem.TooLarge, single("""{"version":1,"body":{"type":"divider"}}$padding""").problem)
        val version = single("""{"version":2,"body":{"type":"divider"}}""")
        assertEquals(UiSpecProblem.UnsupportedVersion, version.problem)
        assertEquals("$.version", version.path)
    }

    @Test
    fun `node count and nesting are bounded`() {
        val many = (1..UiSpecLimits.NODES).joinToString(",") { """{"type":"divider"}""" }
        val tooMany = single("""{"version":1,"body":{"type":"column","children":[$many]}}""")
        assertEquals(UiSpecProblem.TooManyNodes, tooMany.problem)
        val fits = (1 until UiSpecLimits.NODES).joinToString(",") { """{"type":"divider"}""" }
        assertIs<UiSpecDecoding.Decoded>(decodeUiSpec("""{"version":1,"body":{"type":"column","children":[$fits]}}"""))

        assertEquals(UiSpecProblem.TooDeep, single(nested(UiSpecLimits.DEPTH + 1)).problem)
        assertIs<UiSpecDecoding.Decoded>(decodeUiSpec(nested(UiSpecLimits.DEPTH)))

        val deepJson = "[".repeat(5_000) + "]".repeat(5_000)
        val deep = single("""{"version":1,"state":{"x":$deepJson},"body":{"type":"divider"}}""")
        assertEquals(UiSpecProblem.TooDeep, deep.problem)
    }

    @Test
    fun `diagnostics never print their detail`() {
        val diagnostic = UiSpecDiagnostic("$.body", UiSpecProblem.Malformed, "secret value")

        assertFalse("secret" in diagnostic.toString())
        assertEquals("$.body: the structure does not match the spec format (secret value)", diagnostic.describe())
    }

    @Test
    fun `specs are indented for editing and compacted for storage within the limits`() {
        val compact = """{"version":1,"body":{"type":"text","text":"a: b"}}"""
        val indented = assertNotNull(formatUiSpec(compact, isIndented = true))

        assertTrue(indented.lines().size > 1)
        assertEquals(compact, formatUiSpec(indented, isIndented = false))
        assertNull(formatUiSpec("{", isIndented = true))
        assertNull(formatUiSpec("[".repeat(5_000) + "]".repeat(5_000), isIndented = false))
    }

    private fun single(text: String): UiSpecDiagnostic =
        assertIs<UiSpecDecoding.Rejected>(decodeUiSpec(text)).diagnostics.single()

    private fun malformed(text: String): UiSpecDiagnostic =
        single(text).also { assertEquals(UiSpecProblem.Malformed, it.problem, it.describe()) }

    private fun nested(depth: Int): String {
        val open = """{"type":"column","children":[""".repeat(depth - 1)
        val close = "]}".repeat(depth - 1)
        return """{"version":1,"body":$open{"type":"divider"}$close}"""
    }

    private fun args(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private companion object {
        const val PR_MAP_ARGS = """{"state":"{{state.filter}}"}"""

        val PrMapSpec = """
            {
              "version": 1,
              "title": "PR map",
              "state": {"filter": "open"},
              "data": {"prs": {"method": "prs", "args": $PR_MAP_ARGS}},
              "modals": {
                "details": {"title": "PR {{args.number}}", "body": {"type": "markdown", "text": "{{args.body}}"}}
              },
              "body": {"type": "column", "children": [
                {"type": "row", "children": [
                  {"type": "select", "bind": "filter", "options": [
                    {"value": "open", "label": "Open"}, {"value": "all", "label": "All"}]},
                  {"type": "button", "label": "Refresh", "style": "primary",
                   "action": {"type": "reload", "source": "prs"}}
                ]},
                {"type": "if", "when": "{{status.prs.loading}}", "then": {"type": "progress"}, "else": {
                  "type": "table", "rows": "{{data.prs.items}}", "key": "number",
                  "columns": [
                    {"title": "#", "value": "{{item.number}}", "weight": 0.5},
                    {"title": "Title", "value": "{{item.title}}", "weight": 3}],
                  "onRowClick": {"type": "open", "modal": "details",
                    "args": {"number": "{{item.number}}", "body": "{{item.body}}"}}
                }},
                {"type": "diagram", "source": "{{data.prs.plantuml}}"},
                {"type": "button", "label": "Ask agent", "action": {"type": "sequence", "actions": [
                  {"type": "prompt", "text": "Prioritize {{data.prs.numbers}}", "submit": true},
                  {"type": "close"}]}}
              ]}
            }
        """.trimIndent()

        val EveryType = """
            {
              "version": 1,
              "state": {"on": false, "name": "", "day": ""},
              "body": {"type": "column", "gap": "large", "children": [
                {"type": "divider"},
                {"type": "spacer", "size": "small"},
                {"type": "text", "text": "Title", "style": "heading", "tone": "brand"},
                {"type": "markdown", "text": "**bold**"},
                {"type": "badge", "text": "ok", "tone": "success"},
                {"type": "progress", "value": "0.5", "label": "Half"},
                {"type": "keyValue", "items": [{"key": "a", "value": "b"}]},
                {"type": "empty", "title": "Nothing", "description": "Yet"},
                {"type": "card", "title": "Card", "children": [{"type": "divider"}]},
                {"type": "section", "title": "Section", "description": "d", "children": []},
                {"type": "list", "items": "{{state.rows}}", "item": {"type": "text", "text": "{{item}}"},
                 "key": "id", "empty": {"type": "empty", "title": "None"}},
                {"type": "toggle", "bind": "on", "label": "On"},
                {"type": "textField", "bind": "name", "label": "Name", "placeholder": "x", "multiline": true},
                {"type": "native", "widget": "date", "bind": "day", "label": "Day"},
                {"type": "web", "html": "<p>x</p>", "bridge": true, "data": "{{state.name}}"},
                {"type": "button", "label": "All", "style": "danger", "icon": "play", "enabledWhen": "{{state.on}}",
                 "confirm": "Sure?", "id": "all", "action": {"type": "sequence", "actions": [
                  {"type": "call", "method": "m", "args": {"a": 1}, "into": "name"},
                  {"type": "set", "key": "on", "value": true},
                  {"type": "open", "surface": "other", "presentation": "panel", "args": {"x": "y"}},
                  {"type": "openUrl", "url": "https://example.com"},
                  {"type": "copy", "text": "c"},
                  {"type": "saveFile", "text": "t", "name": "a.txt", "extensions": ["txt"]},
                  {"type": "reveal", "path": "/tmp"},
                  {"type": "prompt", "text": "p"},
                  {"type": "reload", "source": "s"},
                  {"type": "close"}]}}
              ]}
            }
        """.trimIndent()
    }
}
