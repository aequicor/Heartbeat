package io.aequicor.heartbeat.feature.serviceasui.impl.presentation

import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiAction
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecCheck
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecDecoding
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecLimits
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecOrigin
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecRules
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.checkUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.decodeUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ButtonStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.WebHeightUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiEvaluatorTest {
    private val evaluator = UiEvaluator(spec(PrMap))

    @Test
    fun `content sized web nodes retain their mode through evaluation`() {
        val app =
            UiEvaluator(
                spec(
                    """{"version":1,"body":{
            "type":"web","html":"<main>App</main>","height":"content"}}""",
                ),
            )
        val node = assertIs<UiNodeUi.Web>(app.body(UiValues()))
        assertEquals(WebHeightUi.Content, node.height)
    }

    @Test
    fun `a loading source shows progress and a ready one its table and diagram`() {
        val loading = column(evaluator.body(values(UiSourceStatus.Loading)))
        assertIs<UiNodeUi.Progress>(loading.children[1])

        val ready = column(evaluator.body(values(UiSourceStatus.Ready)))
        val select = assertIs<UiNodeUi.Row>(ready.children[0]).children[0]
        assertEquals("open", assertIs<UiNodeUi.Select>(select).selected)
        val table = assertIs<UiNodeUi.Table>(ready.children[1])
        assertEquals(listOf("#", "Title"), table.columns.map { it.title })
        assertEquals(listOf("12", "15"), table.rows.map { it.key })
        assertEquals(listOf("15", "Fix login"), table.rows[1].cells)
        val press = table.rows[1].press!!
        assertEquals(UiScope(prs[1], 1), press.scope)
        assertEquals("b.1.else[15]", press.origin)
        assertEquals("@startuml\nA -> B\n@enduml", assertIs<UiNodeUi.Diagram>(ready.children[2]).source)
        val button = assertIs<UiNodeUi.Button>(ready.children[3])
        assertEquals("Ask about 2 PRs", button.label)
        assertEquals(ButtonStyleUi.Primary, button.style)
        assertTrue(button.isEnabled)
        assertFalse(button.isBusy)
    }

    @Test
    fun `a spec without quick actions evaluates to none`() {
        assertEquals(emptyList(), evaluator.quickActions(values(UiSourceStatus.Ready)))
    }

    @Test
    fun `quick actions resolve their labels in order and keep the press for their action`() {
        val quick = UiEvaluator(spec(QuickSpec))
        val ready = values(UiSourceStatus.Ready).copy(state = mapOf("stage" to JsonPrimitive("plan")))

        val actions = quick.quickActions(ready)

        assertEquals(listOf("next", "later", "always"), actions.map { it.id })
        val next = actions[0]
        assertEquals("Next plan (2)", next.label)
        assertEquals(ButtonStyleUi.Primary, next.style)
        assertEquals("play", next.icon)
        assertEquals("Start plan?", next.confirm)
        assertTrue(next.isEnabled)
        assertFalse(next.isBusy)
        assertEquals("q.next", next.press.origin)
        assertEquals(UiScope(), next.press.scope)
        assertEquals(
            UiAction.Call("advance", buildJsonObject { put("stage", "{{state.stage}}") }, into = "stage"),
            next.press.action,
        )
        assertEquals(ButtonStyleUi.Secondary, actions[1].style)
        assertNull(actions[1].confirm)
        assertNull(actions[1].icon)
    }

    @Test
    fun `visibility hides a quick action and enablement only disables it`() {
        val quick = UiEvaluator(spec(QuickSpec))
        val loading = UiValues(
            state = mapOf("stage" to JsonPrimitive("plan")),
            status = mapOf("prs" to UiSourceStatus.Loading),
        )
        val hidden = loading.copy(state = mapOf("stage" to JsonPrimitive("")))

        val shown = quick.quickActions(loading)
        assertEquals(listOf("next", "later", "always"), shown.map { it.id })
        assertEquals(listOf(false, false, true), shown.map { it.isEnabled })

        assertEquals(listOf("always"), quick.quickActions(hidden).map { it.id })
    }

    @Test
    fun `a running quick action is busy and its text is bounded`() {
        val quick = UiEvaluator(spec(QuickSpec))
        val busy = values(UiSourceStatus.Ready)
            .copy(state = mapOf("stage" to JsonPrimitive("x".repeat(UiSpecLimits.QUICK_ACTION_CHARS * 2))))
            .copy(busy = setOf("q.always", "later"))

        val actions = quick.quickActions(busy)

        assertEquals(listOf(false, false, true), actions.map { it.isBusy })
        assertEquals(UiSpecLimits.QUICK_ACTION_CHARS, actions[0].label.length)
        assertEquals(UiSpecLimits.QUICK_ACTION_CHARS, actions[0].confirm!!.length)
    }

    @Test
    fun `progress keeps a share in range and is indeterminate without a finite number`() {
        val evaluator = UiEvaluator(
            spec("""{"version":1,"state":{"p":0},"body":{"type":"progress","value":"{{state.p}}"}}"""),
        )
        fun progress(value: JsonElement) =
            assertIs<UiNodeUi.Progress>(evaluator.body(UiValues(state = mapOf("p" to value)))).value

        assertEquals(0.25f, progress(JsonPrimitive(0.25)))
        assertEquals(1f, progress(JsonPrimitive(7)))
        assertNull(progress(JsonPrimitive(Double.NaN)))
        assertNull(progress(JsonPrimitive(Double.POSITIVE_INFINITY)))
        assertNull(progress(JsonPrimitive("soon")))
    }

    @Test
    fun `busy and disabled buttons follow values`() {
        val busy = column(evaluator.body(values(UiSourceStatus.Ready).copy(busy = setOf("ask"))))
        assertTrue(assertIs<UiNodeUi.Button>(busy.children[3]).isBusy)

        val failed = column(evaluator.body(values(UiSourceStatus.Failed("gh missing"))))
        assertFalse(assertIs<UiNodeUi.Button>(failed.children[3]).isEnabled)
    }

    @Test
    fun `lists expand over arrays with stable keys an empty view and a count of hidden elements`() {
        val evaluator = UiEvaluator(
            spec(
                """{"version":1,"state":{"rows":[]},"body":{"type":"list","items":"{{state.rows}}","key":"id",
                   "item":{"type":"text","text":"{{index}}: {{item.name}}"},
                   "empty":{"type":"empty","title":"None"}}}""",
            ),
        )
        fun rows(vararg ids: String) = JsonArray(
            ids.map { id ->
                buildJsonObject {
                    put("id", id)
                    put("name", "n$id")
                }
            },
        )

        assertEquals("None", assertIs<UiNodeUi.Empty>(evaluator.body(UiValues(state = mapOf("rows" to rows())))).title)
        val keyed = column(evaluator.body(UiValues(state = mapOf("rows" to rows("a", "b")))))
        assertEquals(listOf("b[a]", "b[b]"), keyed.children.map { it.key })
        assertEquals("1: nb", assertIs<UiNodeUi.Text>(keyed.children[1]).text)
        val duplicated = column(evaluator.body(UiValues(state = mapOf("rows" to rows("a", "a")))))
        assertEquals(listOf("b[0]", "b[1]"), duplicated.children.map { it.key })

        val many = rows(*Array(UiSpecLimits.LIST_ITEMS + 5) { "k$it" })
        val long = column(evaluator.body(UiValues(state = mapOf("rows" to many))))
        assertEquals(UiSpecLimits.LIST_ITEMS + 1, long.children.size)
        assertEquals(5, assertIs<UiNodeUi.More>(long.children.last()).count)
    }

    @Test
    fun `values become text truthiness and typed arguments`() {
        val bindings = UiBindings(
            UiValues(
                state = mapOf(
                    "n" to JsonPrimitive(3),
                    "flag" to JsonPrimitive(false),
                    "nothing" to JsonNull,
                    "list" to buildJsonArray { add(JsonPrimitive(1)) },
                    "zero" to JsonPrimitive("0"),
                ),
                args = buildJsonObject { put("pr", 7) },
                facts = UiFacts("android", hasSession = true),
            ),
        )
        val scope = UiScope()

        assertEquals(
            "3 false  [1]",
            bindings.text("{{state.n}} {{state.flag}} {{state.nothing}} {{state.list}}", scope),
        )
        assertEquals("android true", bindings.text("{{context.platform}} {{context.hasSession}}", scope))
        assertEquals("missing:", bindings.text("missing:{{state.absent.deep}}", scope))
        assertTrue(bindings.isTrue("{{state.n}}", scope))
        assertFalse(bindings.isTrue("{{state.flag}}", scope))
        assertTrue(bindings.isTrue("{{state.zero}}", scope))
        assertFalse(bindings.isTrue("{{state.absent}}", scope))
        val arguments: JsonElement = Json.parseToJsonElement(
            """{"number":"{{args.pr}}","label":"PR {{args.pr}}","raw":true,"nested":["{{state.list}}"]}""",
        )
        assertEquals(
            Json.parseToJsonElement("""{"number":7,"label":"PR 7","raw":true,"nested":[[1]]}"""),
            bindings.json(arguments, scope),
        )
    }

    @Test
    fun `modals read their arguments and unknown modals are null`() {
        val values = values(UiSourceStatus.Ready).copy(args = buildJsonObject { put("number", 15) })

        val modal = evaluator.modal("details", values)!!
        assertEquals("PR 15", modal.title)
        assertEquals("Body of 15", assertIs<UiNodeUi.Markdown>(modal.body).text)
        assertTrue(modal.isWide)
        assertNull(evaluator.modal("nothing", values))
    }

    @Test
    fun `a press keeps the action of the spec`() {
        val ready = column(evaluator.body(values(UiSourceStatus.Ready)))
        val button = assertIs<UiNodeUi.Button>(ready.children[3])

        assertEquals("ask", button.press.origin)
        assertIs<UiAction.Prompt>(button.press.action)
        assertFalse("Ask about" in button.press.toString())
    }

    @Test
    fun `nested lists share one expansion budget despite a small valid spec`() {
        var body = """{"type":"text","text":"{{item}}"}"""
        repeat(4) { body = """{"type":"list","items":"{{args.rows}}","item":$body}""" }
        val json = """{"version":1,"body":$body}"""
        assertIs<UiSpecCheck.Valid>(checkUiSpec(json, UiSpecRules(UiSpecOrigin.Inline)))
        val values = UiValues(args = JsonObject(mapOf("rows" to JsonArray(List(10) { JsonPrimitive(it) }))))
        val tree = column(UiEvaluator(spec(json)).body(values))
        assertTrue(tree.elements() <= UiSpecLimits.RENDER_ELEMENTS)
        assertTrue(tree.flattened().any { it is UiNodeUi.More && it.count > 0 })
    }

    @Test
    fun `table cells and key value pairs share the expanded element budget`() {
        val columns = List(12) { """{"title":"C$it","value":"{{item}}"}""" }.joinToString(",")
        val table = """{"type":"table","rows":"{{args.rows}}","columns":[$columns]}"""
        val pairs = List(64) { """{"key":"K$it","value":"{{item}}"}""" }.joinToString(",")
        val body = """{"type":"list","items":"{{args.rows}}","item":{"type":"column","children":[
            {"type":"keyValue","items":[$pairs]},$table]}}"""
        val evaluator = UiEvaluator(spec("""{"version":1,"body":$body}"""))
        val rows = JsonArray(List(200) { JsonPrimitive(it) })
        val tree = column(evaluator.body(UiValues(args = JsonObject(mapOf("rows" to rows)))))
        assertTrue(tree.elements() <= UiSpecLimits.RENDER_ELEMENTS)
        val tables = tree.flattened().filterIsInstance<UiNodeUi.Table>()
        assertTrue(tables.isNotEmpty())
        assertTrue(tables.any { it.hidden > 0 })
        tables.forEach { assertEquals(200, it.rows.size + it.hidden) }
    }

    @Test
    fun `display text is bounded across bindings and across repeated nodes`() {
        val json = """{"version":1,"body":{"type":"list","items":"{{args.rows}}",
            "item":{"type":"text","text":"{{args.text}}{{args.text}}{{args.text}}{{args.text}}"}}}"""
        val values = UiValues(
            args = JsonObject(
                mapOf(
                    "rows" to JsonArray(List(200) { JsonPrimitive(it) }),
                    "text" to JsonPrimitive("x".repeat(8192)),
                ),
            ),
        )
        val tree = column(UiEvaluator(spec(json)).body(values))
        val texts = tree.flattened().filterIsInstance<UiNodeUi.Text>()
        assertEquals(UiSpecLimits.RENDER_CHARS, texts.sumOf { it.text.length })
        assertEquals("x".repeat(12), UiBindings(values).text("{{args.text}}{{args.text}}", UiScope(), 12))
    }

    private fun UiNodeUi.flattened(): List<UiNodeUi> = listOf(this) + when (this) {
        is UiNodeUi.Column -> children.flatMap { it.flattened() }
        is UiNodeUi.Row -> children.flatMap { it.flattened() }
        is UiNodeUi.Card -> children.flatMap { it.flattened() }
        is UiNodeUi.Section -> children.flatMap { it.flattened() }
        else -> emptyList()
    }

    private fun UiNodeUi.elements(): Int = flattened().sumOf { node ->
        1 + when (node) {
            is UiNodeUi.Table -> node.columns.size + node.rows.sumOf { 1 + it.cells.size }
            is UiNodeUi.KeyValue -> node.items.size
            is UiNodeUi.Select -> node.options.size
            else -> 0
        }
    }

    private fun values(status: UiSourceStatus) = UiValues(
        data = mapOf(
            "prs" to buildJsonObject {
                put("items", JsonArray(prs))
                put("plantuml", "@startuml\nA -> B\n@enduml")
                put("count", 2)
            },
        ),
        state = mapOf("filter" to JsonPrimitive("open")),
        status = mapOf("prs" to status),
    )

    private fun column(node: UiNodeUi?): UiNodeUi.Column = assertIs<UiNodeUi.Column>(node)

    private fun spec(text: String): UiSpec = assertIs<UiSpecDecoding.Decoded>(decodeUiSpec(text)).spec

    private companion object {
        val prs: List<JsonObject> = listOf(
            buildJsonObject {
                put("number", 12)
                put("title", "Add map")
            },
            buildJsonObject {
                put("number", 15)
                put("title", "Fix login")
            },
        )

        val QuickSpec = """
            {"version":1,"state":{"stage":"plan"},
             "data":{"prs":{"method":"prs"}},
             "quickActions":[
               {"id":"next","label":"Next {{state.stage}} ({{data.prs.count}})","style":"primary","icon":"play",
                "confirm":"Start {{state.stage}}?","enabledWhen":"{{status.prs.ready}}","visibleWhen":"{{state.stage}}",
                "action":{"type":"call","method":"advance","args":{"stage":"{{state.stage}}"},"into":"stage"}},
               {"id":"later","label":"Later","visibleWhen":"{{state.stage}}","enabledWhen":"{{status.prs.ready}}",
                "action":{"type":"reload","source":"prs"}},
               {"id":"always","label":"Always","action":{"type":"set","key":"stage","value":"plan"}}],
             "body":{"type":"divider"}}
        """.trimIndent()

        val PrMap = """
            {"version":1,"state":{"filter":"open"},
             "data":{"prs":{"method":"prs","args":{"state":"{{state.filter}}"}}},
             "modals":{"details":{"title":"PR {{args.number}}","wide":true,
               "body":{"type":"markdown","text":"Body of {{args.number}}"}}},
             "body":{"type":"column","children":[
               {"type":"row","children":[{"type":"select","bind":"filter","options":[{"value":"open","label":"Open"}]}]},
               {"type":"if","when":"{{status.prs.loading}}","then":{"type":"progress"},"else":{
                 "type":"table","rows":"{{data.prs.items}}","key":"number",
                 "columns":[{"title":"#","value":"{{item.number}}","weight":0.5},{"title":"Title","value":"{{item.title}}"}],
                 "onRowClick":{"type":"open","modal":"details","args":{"number":"{{item.number}}"}}}},
               {"type":"diagram","source":"{{data.prs.plantuml}}"},
               {"type":"button","id":"ask","label":"Ask about {{data.prs.count}} PRs","style":"primary",
                "enabledWhen":"{{status.prs.ready}}","action":{"type":"prompt","text":"Rank PRs","submit":true}}
             ]}}
        """.trimIndent()
    }
}
