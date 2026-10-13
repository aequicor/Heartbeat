package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiIconNames
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecDecoding
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.decodeUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiEvaluator
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiSourceStatus
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiValues
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.NoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceState
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SurfaceUiTest {
    private val previousLocale = Locale.getDefault()

    @BeforeTest
    fun useEnglish() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `a surface draws its nodes in both themes on desktop and touch dimensions`() {
        for (dimensions in listOf(HbDimensions.Desktop, HbDimensions.Mobile)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(360f, 1800f)) {
                    setContent { Themed(dark, dimensions) { SurfaceContent(state(), {}) } }
                    onNodeWithText("Pull requests").assertIsDisplayed()
                    onNodeWithText("Add map").assertIsDisplayed()
                    onNodeWithText("2 open").assertIsDisplayed()
                    onNodeWithText("Refresh").assertIsDisplayed()
                    // A field never pushes the button beside it out of a narrow row.
                    onNodeWithText("Save").assertIsDisplayed()
                    onNode(hasContentDescription("Priority: High")).assertIsDisplayed()
                    onNodeWithContentDescription("Drafts only").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun `presses, inputs and notices reach the store`() = runSkikoComposeUiTest(size = Size(720f, 1800f)) {
        val intents = mutableListOf<SurfaceIntent>()
        val notice = NoticeUi.Failed(FailureUi.TimedOut)
        setContent { Themed { SurfaceContent(state().copy(notice = notice), { intents += it }) } }

        onNodeWithText("Refresh").performClick()
        assertIs<SurfaceIntent.Press>(intents.single())
        onNodeWithText("Fix login").performClick()
        assertEquals(2, intents.filterIsInstance<SurfaceIntent.Press>().size)
        onNodeWithText("The method took too long").assertIsDisplayed()
        onNodeWithText("Dismiss").performClick()
        assertTrue(SurfaceIntent.DismissNotice in intents)
        onNodeWithContentDescription("Drafts only").performClick()
        assertEquals(SurfaceIntent.Input("drafts", JsonPrimitive(true)), intents.last())
    }

    @Test
    fun `a confirmed button runs once its question is answered`() = runSkikoComposeUiTest(size = Size(720f, 1800f)) {
        val intents = mutableListOf<SurfaceIntent>()
        setContent { Themed { SurfaceContent(state(), { intents += it }) } }

        onNodeWithText("Delete").performClick()
        assertTrue(intents.isEmpty())
        onNodeWithText("Remove the draft?").assertIsDisplayed()
        onAllNodes(hasText("Delete") and hasClickAction()).onLast().performClick()
        assertIs<SurfaceIntent.Press>(intents.single())
    }

    @Test
    fun `a date reaches the state only while it is a calendar day`() = runSkikoComposeUiTest(
        size = Size(720f, 1800f),
    ) {
        val intents = mutableListOf<SurfaceIntent>()
        setContent { Themed { SurfaceContent(state(), { intents += it }) } }

        val field = onNode(hasSetTextAction() and hasContentDescription("Due"))
        field.performTextInput("2026-1")
        assertEquals(SurfaceIntent.Input("due", JsonPrimitive("")), intents.last())
        onNodeWithText("Enter a date as YYYY-MM-DD").assertIsDisplayed()
        field.performTextInput("0-12")
        assertEquals(SurfaceIntent.Input("due", JsonPrimitive("2026-10-12")), intents.last())
    }

    @Test
    fun `modals close with their button`() = runSkikoComposeUiTest(size = Size(720f, 1800f)) {
        val intents = mutableListOf<SurfaceIntent>()
        val state = state()
        val modal = UiEvaluator(spec()).modal("details", state.values)
        setContent { Themed { SurfaceContent(state.copy(modal = modal), { intents += it }) } }

        onNodeWithText("Details").assertIsDisplayed()
        onNodeWithText("Close").performClick()
        assertEquals(listOf<SurfaceIntent>(SurfaceIntent.DismissModal), intents)
    }

    @Test
    fun `markdown links go to the store`() = runSkikoComposeUiTest(size = Size(720f, 1800f)) {
        val intents = mutableListOf<SurfaceIntent>()
        setContent { Themed { SurfaceContent(state(), { intents += it }) } }

        onNodeWithText("Open PR 12", substring = true).performMouseInput { click(centerLeft + Offset(LINK_X, 0f)) }
        assertEquals(listOf<SurfaceIntent>(SurfaceIntent.OpenLink("https://example.com/12")), intents)
    }

    @Test
    fun `web nodes draw their embedded document at a fixed height, a loading state before it, or a note`() =
        runSkikoComposeUiTest(size = Size(720f, 1200f)) {
            val values = UiValues()
            val root = assertIs<UiNodeUi.Column>(
                UiEvaluator((decodeUiSpec(WEB) as UiSpecDecoding.Decoded).spec).body(values),
            )
            var webs by mutableStateOf(persistentMapOf<String, ComposableComponent>())
            setContent { Themed { SurfaceContent(SurfaceState(values = values, root = root), {}, webs = webs) } }
            onNodeWithText("Web content is not available here").assertIsDisplayed()

            webs = persistentMapOf(root.children.single().key to OpeningDocument)
            onNodeWithText("Opening the page…").assertIsDisplayed()

            webs = persistentMapOf(root.children.single().key to TestDocument)
            onNodeWithTag("document").assertIsDisplayed().assertHeightIsEqualTo(HbDimensions.Desktop.webDocumentHeight)
        }

    private object TestDocument : ComposableComponent {
        @Composable
        override fun Content(modifier: Modifier) {
            Box(modifier.testTag("document"))
        }
    }

    @Test
    fun `every icon the reference names draws an icon`() {
        assertEquals(emptyList(), UiIconNames.filter { iconOf(it) == null })
    }

    private fun spec() = (decodeUiSpec(SPEC) as UiSpecDecoding.Decoded).spec

    private fun state(): SurfaceState {
        val values = UiValues(
            data = mapOf(
                "prs" to buildJsonObject {
                    put(
                        "items",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("number", 12)
                                    put("title", "Add map")
                                },
                                buildJsonObject {
                                    put("number", 15)
                                    put("title", "Fix login")
                                },
                            ),
                        ),
                    )
                },
            ),
            state = mapOf(
                "note" to JsonPrimitive(""),
                "drafts" to JsonPrimitive(false),
                "priority" to JsonPrimitive("high"),
                "due" to JsonPrimitive(""),
            ),
            status = mapOf("prs" to UiSourceStatus.Ready),
        )
        return SurfaceState(values = values, root = UiEvaluator(spec()).body(values))
    }

    @Composable
    private fun Themed(
        dark: Boolean = false,
        dimensions: HbDimensions = HbDimensions.Desktop,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            HbTheme(darkTheme = dark, dimensions = dimensions) {
                Box(Modifier.fillMaxSize().background(HbTheme.colors.background)) { content() }
            }
        }
    }

    private companion object {
        const val WEB = """{"version":1,"body":{"type":"column","children":[{"type":"web","html":"<p>Map</p>"}]}}"""

        /** Clicks a link from the start of its line, where the text is, not in the middle of a full-width block. */
        const val LINK_X = 8f

        val SPEC = """
            {"version":1,"state":{"note":"","drafts":false,"priority":"","due":""},"data":{"prs":{"method":"prs"}},
             "body":{"type":"column","children":[
               {"type":"text","text":"Pull requests","style":"heading"},
               {"type":"badge","text":"2 open","tone":"success"},
               {"type":"table","rows":"{{data.prs.items}}","key":"number",
                "columns":[{"title":"#","value":"{{item.number}}"},{"title":"Title","value":"{{item.title}}","weight":3}],
                "onRowClick":{"type":"open","modal":"details"}},
               {"type":"row","children":[
                 {"type":"textField","bind":"note","placeholder":"A note for the agent about the next steps"},
                 {"type":"button","label":"Save","action":{"type":"set","key":"note","value":""}}]},
               {"type":"toggle","bind":"drafts","label":"Drafts only"},
               {"type":"select","bind":"priority","label":"Priority",
                "options":[{"value":"high","label":"High"},{"value":"low","label":"Low"}]},
               {"type":"native","widget":"date","bind":"due","label":"Due"},
               {"type":"markdown","text":"[Open PR 12](https://example.com/12)"},
               {"type":"row","children":[
                 {"type":"button","label":"Refresh","style":"primary","action":{"type":"reload","source":"prs"}},
                 {"type":"button","label":"Delete","style":"danger","confirm":"Remove the draft?",
                  "action":{"type":"set","key":"note","value":""}},
                 {"type":"button","label":"Open","action":{"type":"openUrl","url":"https://github.com"}}]}
             ]},
             "modals":{"details":{"title":"Details","body":{"type":"divider"}}}}
        """.trimIndent()
    }
}
