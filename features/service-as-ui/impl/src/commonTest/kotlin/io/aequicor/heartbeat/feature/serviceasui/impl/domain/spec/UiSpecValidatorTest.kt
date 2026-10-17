package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UiSpecValidatorTest {
    private val library = UiSpecRules(UiSpecOrigin.Library, UiPlacement.Start, methods = setOf("prs"), setOf("details"))
    private val card = UiSpecRules(UiSpecOrigin.Inline)
    private val app = UiSpecRules(
        UiSpecOrigin.Library,
        UiPlacement.Card,
        methods = setOf("snapshot", "advance"),
        surfaces = setOf("details"),
    )

    @Test
    fun `a consistent library surface is valid`() {
        val check = assertIs<UiSpecCheck.Valid>(checkUiSpec(PrMap, library))

        assertEquals(emptyList(), check.warnings)
    }

    @Test
    fun `calls are warnings while the owner's methods are unknown and errors when it lacks them`() {
        val unknown = assertIs<UiSpecCheck.Valid>(checkUiSpec(PrMap, library.copy(methods = null)))
        assertEquals(
            listOf(UiSpecProblem.UnknownMethod, UiSpecProblem.UnknownMethod),
            unknown.warnings.map { it.problem },
        )
        assertTrue(unknown.warnings.all { it.isWarning })

        val missing = problems(PrMap, library.copy(methods = setOf("other")))
        assertEquals(
            listOf(
                "$.data.prs.method" to UiSpecProblem.UnknownMethod,
                "$.body.children[4].action.actions[0].method" to UiSpecProblem.UnknownMethod,
            ),
            missing,
        )
        assertIs<UiSpecCheck.Invalid>(checkUiSpec(PrMap, library.copy(methods = setOf("other"))))
    }

    @Test
    fun `references must be declared`() {
        val spec = spec(
            """{"type":"column","children":[
                {"type":"text","text":"{{data.missing}} {{state.nope}} {{status.prs.done}}"},
                {"type":"toggle","bind":"absent","label":"x"},
                {"type":"button","label":"a","action":{"type":"open","modal":"ghost"}},
                {"type":"button","label":"b","action":{"type":"open","surface":"elsewhere","presentation":"panel"}},
                {"type":"button","label":"c","action":{"type":"reload","source":"missing"}},
                {"type":"button","label":"d","action":{"type":"set","key":"absent","value":1}}
            ]}""",
            data = """{"prs":{"method":"prs"}}""",
        )

        assertEquals(
            listOf(
                "$.body.children[0].text" to UiSpecProblem.UnknownSource,
                "$.body.children[0].text" to UiSpecProblem.UnknownStateKey,
                "$.body.children[0].text" to UiSpecProblem.InvalidBinding,
                "$.body.children[1].bind" to UiSpecProblem.UnknownStateKey,
                "$.body.children[2].action.modal" to UiSpecProblem.UnknownModal,
                "$.body.children[3].action.surface" to UiSpecProblem.UnknownSurface,
                "$.body.children[4].action.source" to UiSpecProblem.UnknownSource,
                "$.body.children[5].action.key" to UiSpecProblem.UnknownStateKey,
            ),
            problems(spec, library),
        )
    }

    @Test
    fun `item and index exist only inside lists and tables`() {
        val spec = spec(
            """{"type":"column","children":[
                {"type":"text","text":"{{item.title}}"},
                {"type":"list","items":"{{state.rows}}","item":{"type":"text","text":"{{index}}: {{item.title}}"}},
                {"type":"table","rows":"{{state.rows}}","columns":[{"title":"{{item.x}}","value":"{{item.title}}"}],
                 "onRowClick":{"type":"copy","text":"{{item.url}}"}},
                {"type":"text","text":"{{index.x}}"}
            ]}""",
            state = """{"rows":[]}""",
        )

        assertEquals(
            listOf(
                "$.body.children[0].text" to UiSpecProblem.OutOfScope,
                "$.body.children[2].columns[0].title" to UiSpecProblem.OutOfScope,
                "$.body.children[3].text" to UiSpecProblem.OutOfScope,
            ),
            problems(spec, library),
        )
    }

    @Test
    fun `binding fields need exactly one binding`() {
        val spec = spec(
            """{"type":"column","children":[
                {"type":"list","items":"rows","item":{"type":"divider"}},
                {"type":"if","when":"{{state.on}} and more","then":{"type":"divider"}},
                {"type":"progress","value":"2"},
                {"type":"progress","value":"0.25"},
                {"type":"text","text":"{{state.on"},
                {"type":"text","text":"{{context.platform}} {{context.user}}"}
            ]}""",
            state = """{"on":true}""",
        )

        assertEquals(
            listOf(
                "$.body.children[0].items" to UiSpecProblem.InvalidBinding,
                "$.body.children[1].when" to UiSpecProblem.InvalidBinding,
                "$.body.children[2].value" to UiSpecProblem.InvalidValue,
                "$.body.children[4].text" to UiSpecProblem.InvalidBinding,
                "$.body.children[5].text" to UiSpecProblem.InvalidBinding,
            ),
            problems(spec, library),
        )
    }

    @Test
    fun `cards without an owner cannot run code or send prompts`() {
        val spec = spec(
            """{"type":"column","children":[
                {"type":"button","label":"a","action":{"type":"call","method":"prs"}},
                {"type":"button","label":"b","action":{"type":"prompt","text":"go","submit":true}},
                {"type":"button","label":"c","action":{"type":"reveal","path":"/tmp"}},
                {"type":"button","label":"d","action":{"type":"open","surface":"details"}},
                {"type":"button","label":"e","action":{"type":"prompt","text":"{{args.pr}}"}}
            ]}""",
            data = """{"prs":{"method":"prs"}}""",
        )

        assertEquals(
            listOf(
                "$.data" to UiSpecProblem.OriginForbidden,
                "$.body.children[0].action" to UiSpecProblem.OriginForbidden,
                "$.body.children[1].action.submit" to UiSpecProblem.OriginForbidden,
                "$.body.children[2].action" to UiSpecProblem.OriginForbidden,
                "$.body.children[3].action.surface" to UiSpecProblem.OriginForbidden,
            ),
            problems(spec, card),
        )
    }

    @Test
    fun `web documents work in library placements but anonymous cards cannot obtain a bridge`() {
        val web = """{"type":"web","html":"<p>x</p>","bridge":true,"height":"content"}"""
        val inline = spec(web)
        val inModal = spec("""{"type":"divider"}""", modals = """{"view":{"title":"V","body":$web}}""")
        val twice = spec("""{"type":"column","children":[$web,$web]}""")

        UiPlacement.entries.forEach { placement ->
            assertEquals(emptyList(), problems(inline, library.copy(placement = placement)))
        }
        assertEquals(
            listOf("$.body" to UiSpecProblem.PlacementForbidden, "$.body.bridge" to UiSpecProblem.OriginForbidden),
            problems(inline, card),
        )
        assertEquals(emptyList(), problems(inline, library.copy(placement = UiPlacement.Panel)))
        assertEquals(emptyList(), problems(inModal, library))
        assertEquals(
            listOf("$.modals.view.body.bridge" to UiSpecProblem.OriginForbidden),
            problems(inModal, card),
        )
        assertEquals(
            listOf("$.body.children[1]" to UiSpecProblem.LimitExceeded),
            problems(twice, library.copy(placement = UiPlacement.Modal)),
        )
    }

    @Test
    fun `focusing a card requires a library owner and a known surface`() {
        val focus = spec(
            """{"type":"button","label":"Continue","action":{"type":"focusCard","surface":"details"}}""",
        )
        assertEquals(emptyList(), problems(focus, library))
        assertEquals(listOf("$.body.action" to UiSpecProblem.OriginForbidden), problems(focus, card))
        assertEquals(
            listOf("$.body.action.surface" to UiSpecProblem.UnknownSurface),
            problems(focus, library.copy(surfaces = emptySet())),
        )
    }

    @Test
    fun `literal values are checked`() {
        val spec = spec(
            """{"type":"column","children":[
                {"type":"diagram","source":"```plantuml\nA -> B\n```"},
                {"type":"button","label":"u","action":{"type":"openUrl","url":"file:///etc/passwd"}},
                {"type":"button","label":"v","action":{"type":"openUrl","url":"{{state.link}}"}},
                {"type":"button","label":"s","action":{"type":"saveFile","text":"t","name":"../x","extensions":["j.s"]}},
                {"type":"select","bind":"link","options":[{"value":"a","label":"A"},{"value":"a","label":"B"}]},
                {"type":"native","widget":"date","bind":"link","extensions":["json"]},
                {"type":"button","label":"o","id":"bad id","action":{"type":"open","modal":"m","presentation":"panel"}},
                {"type":"button","label":"q","action":{"type":"sequence","actions":[]}},
                {"type":"button","label":" ","icon":"copy","action":{"type":"copy","text":"x"}}
            ]}""",
            state = """{"link":""}""",
            modals = """{"m":{"title":"M","body":{"type":"divider"}},
                "bad name":{"title":"N","body":{"type":"divider"}}}""",
        )

        assertEquals(
            listOf(
                "$.modals.bad name" to UiSpecProblem.InvalidName,
                "$.body.children[0].source" to UiSpecProblem.DiagramFence,
                "$.body.children[1].action.url" to UiSpecProblem.InvalidUrl,
                "$.body.children[3].action.name" to UiSpecProblem.InvalidValue,
                "$.body.children[3].action.extensions" to UiSpecProblem.InvalidValue,
                "$.body.children[4].options" to UiSpecProblem.InvalidValue,
                "$.body.children[5].extensions" to UiSpecProblem.InvalidValue,
                "$.body.children[6].action.presentation" to UiSpecProblem.InvalidValue,
                "$.body.children[6].id" to UiSpecProblem.InvalidName,
                "$.body.children[7].action.actions" to UiSpecProblem.LimitExceeded,
                "$.body.children[8].label" to UiSpecProblem.InvalidValue,
            ),
            problems(spec, library),
        )
    }

    @Test
    fun `declarations within limits and at most twenty diagnostics`() {
        val sources = (1..UiSpecLimits.SOURCES + 1).joinToString(",") { """"s$it":{"method":"prs"}""" }
        val tooMany = spec("""{"type":"divider"}""", data = "{$sources}")
        assertEquals(listOf("$.data" to UiSpecProblem.LimitExceeded), problems(tooMany, library))

        val bigState = spec("""{"type":"divider"}""", state = """{"x":"${"x".repeat(UiSpecLimits.STATE_BYTES)}"}""")
        assertEquals(listOf("$.state" to UiSpecProblem.LimitExceeded), problems(bigState, library))

        val texts = (1..30).joinToString(",") { """{"type":"text","text":"{{state.missing$it}}"}""" }
        val noisy = spec("""{"type":"column","children":[$texts]}""")
        assertEquals(UiSpecLimits.DIAGNOSTICS, validateUiSpec(decoded(noisy), library).size)
    }

    @Test
    fun `a card app may declare quick actions that call reload and set`() {
        val quick = quickSpec(
            """[{"id":"next","label":"Next {{state.stage}}","style":"primary","icon":"play",
                "visibleWhen":"{{data.snapshot.hasNext}}","enabledWhen":"{{status.snapshot.ready}}",
                "confirm":"Start {{args.name}}?",
                "action":{"type":"sequence","actions":[
                  {"type":"call","method":"advance","args":{"stage":"{{state.stage}}"},"into":"stage"},
                  {"type":"reload","source":"snapshot"},
                  {"type":"sequence","actions":[{"type":"set","key":"stage","value":"{{data.snapshot.stage}}"}]}]}},
               {"id":"reset","label":"Reset","action":{"type":"set","key":"stage","value":"plan"}}]""",
        )

        val check = assertIs<UiSpecCheck.Valid>(checkUiSpec(quick, app))
        assertEquals(emptyList(), check.warnings)
    }

    @Test
    fun `quick actions are only for library card surfaces`() {
        val quick = quickSpec("""[{"id":"a","label":"A","action":{"type":"set","key":"stage","value":"x"}}]""")

        assertEquals(emptyList(), problems(quick, app))
        assertEquals(
            listOf("$.quickActions" to UiSpecProblem.QuickActionForbidden),
            problems(quick, app.copy(placement = UiPlacement.Composer)),
        )
        for (placement in UiPlacement.entries.filter { it != UiPlacement.Card }) {
            assertEquals(
                listOf("$.quickActions" to UiSpecProblem.QuickActionForbidden),
                problems(quick, app.copy(placement = placement)),
                placement.name,
            )
        }
        val anonymous = """{"version":1,"state":{"stage":"plan"},"body":{"type":"divider"},
            "quickActions":[{"id":"a","label":"A","action":{"type":"set","key":"stage","value":"x"}}]}"""
        assertEquals(listOf("$.quickActions" to UiSpecProblem.OriginForbidden), problems(anonymous, card))
    }

    @Test
    fun `quick actions cannot use actions that need the screen or replace the draft`() {
        val forbidden = listOf(
            """{"type":"prompt","text":"go"}""",
            """{"type":"prompt","text":"go","submit":true}""",
            """{"type":"open","modal":"m"}""",
            """{"type":"open","surface":"details"}""",
            """{"type":"focusCard","surface":"details"}""",
            """{"type":"openUrl","url":"https://example.com"}""",
            """{"type":"copy","text":"x"}""",
            """{"type":"saveFile","text":"x"}""",
            """{"type":"reveal","path":"/tmp"}""",
            """{"type":"close"}""",
        )
        val types = listOf(
            "prompt", "prompt", "open", "open", "focusCard", "openUrl", "copy", "saveFile", "reveal", "close",
        )
        forbidden.forEachIndexed { index, action ->
            val direct = quickSpec("""[{"id":"a","label":"A","action":$action}]""", modals = true)
            val inSequence = quickSpec(
                """[{"id":"a","label":"A","action":{"type":"sequence","actions":[
                    {"type":"set","key":"stage","value":"x"},$action]}}]""",
                modals = true,
            )

            assertEquals(
                listOf("$.quickActions[0].action" to UiSpecProblem.QuickActionForbidden),
                problems(direct, app),
                action,
            )
            assertEquals(
                listOf("$.quickActions[0].action.actions[1]" to UiSpecProblem.QuickActionForbidden),
                problems(inSequence, app),
                action,
            )
            val detail = validateUiSpec(decoded(direct), app).single().detail
            assertEquals(types[index], detail, action)
        }
    }

    @Test
    fun `quick action ids labels and sequences are checked`() {
        val quick = quickSpec(
            """[{"id":"bad id","label":"A","action":{"type":"set","key":"stage","value":"x"}},
               {"id":"twice","label":"B","action":{"type":"set","key":"stage","value":"x"}},
               {"id":"twice","label":" ","action":{"type":"set","key":"stage","value":"x"}},
               {"id":"empty","label":"E","action":{"type":"sequence","actions":[]}}]""",
        )

        assertEquals(
            listOf(
                "$.quickActions[0].id" to UiSpecProblem.InvalidName,
                "$.quickActions[2].id" to UiSpecProblem.InvalidValue,
                "$.quickActions[2].label" to UiSpecProblem.InvalidValue,
                "$.quickActions[3].action.actions" to UiSpecProblem.LimitExceeded,
            ),
            problems(quick, app),
        )
    }

    @Test
    fun `quick actions are bounded in number`() {
        val atLimit = quickSpec(setQuickActions(UiSpecLimits.QUICK_ACTIONS))
        val overLimit = quickSpec(setQuickActions(UiSpecLimits.QUICK_ACTIONS + 1))

        assertEquals(emptyList(), problems(atLimit, app))
        assertEquals(listOf("$.quickActions" to UiSpecProblem.LimitExceeded), problems(overLimit, app))
    }

    @Test
    fun `quick action references and bindings are resolved without list elements`() {
        val quick = quickSpec(
            """[{"id":"a","label":"{{item.title}}","visibleWhen":"{{data.nope.ready}}",
                "enabledWhen":"{{state.nope}} {{state.stage}}","confirm":"{{index}}?",
                "action":{"type":"call","method":"missing","args":{"id":"{{item.id}}"},"into":"nope"}},
               {"id":"b","label":"B","action":{"type":"reload","source":"nope"}},
               {"id":"c","label":"C","action":{"type":"set","key":"nope","value":1}}]""",
        )

        assertEquals(
            listOf(
                "$.quickActions[0].label" to UiSpecProblem.OutOfScope,
                "$.quickActions[0].visibleWhen" to UiSpecProblem.UnknownSource,
                "$.quickActions[0].enabledWhen" to UiSpecProblem.UnknownStateKey,
                "$.quickActions[0].enabledWhen" to UiSpecProblem.InvalidBinding,
                "$.quickActions[0].confirm" to UiSpecProblem.OutOfScope,
                "$.quickActions[0].action.method" to UiSpecProblem.UnknownMethod,
                "$.quickActions[0].action.args.id" to UiSpecProblem.OutOfScope,
                "$.quickActions[0].action.into" to UiSpecProblem.UnknownStateKey,
                "$.quickActions[1].action.source" to UiSpecProblem.UnknownSource,
                "$.quickActions[2].action.key" to UiSpecProblem.UnknownStateKey,
            ),
            problems(quick, app),
        )
    }

    private fun setQuickActions(count: Int): String = (1..count).joinToString(",", "[", "]") {
        """{"id":"a$it","label":"A","action":{"type":"set","key":"stage","value":"x"}}"""
    }

    private fun quickSpec(quickActions: String, modals: Boolean = false): String =
        """{"version":1,"state":{"stage":"plan"},"data":{"snapshot":{"method":"snapshot"}},
            "modals":${if (modals) """{"m":{"title":"M","body":{"type":"divider"}}}""" else "{}"},
            "quickActions":$quickActions,"body":{"type":"divider"}}"""

    private fun problems(text: String, rules: UiSpecRules): List<Pair<String, UiSpecProblem>> =
        validateUiSpec(decoded(text), rules).filterNot { it.isWarning }.map { it.path to it.problem }

    private fun decoded(text: String): UiSpec = assertIs<UiSpecDecoding.Decoded>(decodeUiSpec(text)).spec

    private fun spec(body: String, data: String = "{}", state: String = "{}", modals: String = "{}"): String =
        """{"version":1,"state":$state,"data":$data,"modals":$modals,"body":$body}"""

    private companion object {
        val PrMap = """
            {"version":1,"state":{"filter":"open","note":""},
             "data":{"prs":{"method":"prs","args":{"state":"{{state.filter}}"}}},
             "modals":{"details":{"title":"PR {{args.number}}","body":{"type":"markdown","text":"{{args.body}}"}}},
             "body":{"type":"column","children":[
               {"type":"select","bind":"filter","options":[{"value":"open","label":"Open"}]},
               {"type":"textField","bind":"note","label":"Note"},
               {"type":"if","when":"{{status.prs.loading}}","then":{"type":"progress"},"else":{
                 "type":"table","rows":"{{data.prs.items}}","key":"number",
                 "columns":[{"title":"Title","value":"{{item.title}}"}],
                 "onRowClick":{"type":"open","modal":"details","args":{"number":"{{item.number}}"}}}},
               {"type":"diagram","source":"{{data.prs.plantuml}}"},
               {"type":"button","label":"Map","enabledWhen":"{{status.prs.ready}}","action":{"type":"sequence","actions":[
                 {"type":"call","method":"prs","args":{"state":"{{state.filter}}"},"into":"note"},
                 {"type":"open","surface":"details","presentation":"panel"},
                 {"type":"prompt","text":"Rank {{data.prs.numbers}} on {{context.platform}}","submit":true},
                 {"type":"openUrl","url":"https://github.com"}]}}
             ]}}
        """.trimIndent()
    }
}
