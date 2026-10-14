package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppHostIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppHostState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardPhase
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.GapUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.TextStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ToneUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppUiTest {
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
    fun `full web app uses remaining height without a host scroll in both themes and widths`() {
        for (width in listOf(420f, 1280f)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width, 900f)) {
                    val root = UiNodeUi.Column("root", persistentListOf(label("Toolbar"), web), GapUi.Small)
                    setContent {
                        Themed(dark) {
                            SurfaceContent(
                                SurfaceState(root = root),
                                {},
                                Modifier.fillMaxSize(),
                                webs = persistentMapOf(web.key to TestDocument),
                                isApp = true,
                            )
                        }
                    }
                    onNodeWithText("Toolbar").assertIsDisplayed()
                    val bounds = onNodeWithTag("document").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.height > 800f)
                    assertTrue(bounds.bottom <= 900f)
                    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                        .assertCountEquals(0)
                }
            }
        }
    }

    @Test
    fun `wrapped app row stacks on compact windows and keeps a wide document beside native columns`() {
        for (width in listOf(420f, 1280f)) {
            runSkikoComposeUiTest(size = Size(width, 900f)) {
                val controls = UiNodeUi.Column("controls", persistentListOf(label("Controls")), GapUi.Small)
                val root = UiNodeUi.Row("row", persistentListOf(controls, web), GapUi.Small, isWrapped = true)
                setContent {
                    Themed {
                        SurfaceContent(
                            SurfaceState(root = root),
                            {},
                            Modifier.fillMaxSize(),
                            webs = persistentMapOf(web.key to TestDocument),
                            isApp = true,
                        )
                    }
                }
                onNodeWithText("Controls").assertIsDisplayed()
                val bounds = onNodeWithTag("document").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.width >= width / 2f - 10f)
                assertTrue(bounds.height > 800f)
            }
        }
    }

    @Test
    fun `native app has one outer scroll with retained position`() = runSkikoComposeUiTest(size = Size(420f, 480f)) {
        val scroll = ScrollState(100)
        val root = UiNodeUi.Column("root", (0..80).map { label("Row $it") }.toImmutableList(), GapUi.Medium)
        var isShown by mutableStateOf(true)
        setContent {
            Themed {
                if (isShown) {
                    SurfaceContent(
                        SurfaceState(root = root),
                        {},
                        Modifier.fillMaxSize(),
                        isApp = true,
                        scrollState = scroll,
                    )
                }
            }
        }
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(1)
        assertTrue(scroll.maxValue > 0)
        assertEquals(100, scroll.value)
        isShown = false
        waitForIdle()
        isShown = true
        waitForIdle()
        assertEquals(100, scroll.value)
    }

    @Test
    fun `modal web trees shrink grow to the viewport and shrink again without a host scroll`() {
        val roots = listOf(
            web,
            UiNodeUi.Column("column", persistentListOf(web), GapUi.Small),
            UiNodeUi.Card(
                "card",
                null,
                persistentListOf(UiNodeUi.Section("section", "Section", null, persistentListOf(web))),
            ),
        )
        for (root in roots) {
            runSkikoComposeUiTest(size = Size(1280f, 900f)) {
                var naturalHeight by mutableFloatStateOf(180f)
                val document = SizedDocument { naturalHeight }
                setContent {
                    Themed {
                        AppHostView(expanded, hasDocument = true, {}, isDocumentOnly = root is UiNodeUi.Web) { body ->
                            SurfaceContent(
                                SurfaceState(root = root),
                                {},
                                body.testTag("app-content"),
                                webs = persistentMapOf(web.key to document),
                                isApp = true,
                                isContentFill = false,
                            )
                        }
                    }
                }
                val short = onNodeWithTag("app-content").fetchSemanticsNode().boundsInRoot
                assertEquals(180f, onNodeWithTag("document").fetchSemanticsNode().boundsInRoot.height)
                assertTrue(short.height < 350f, "Short ${root.key} should follow its content")
                runOnIdle { naturalHeight = 1800f }
                val long = onNodeWithTag("app-content").fetchSemanticsNode().boundsInRoot
                val viewport = onNodeWithTag("document").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(long.height > 700f && long.height < 900f)
                assertTrue(viewport.height < naturalHeight && viewport.bottom <= long.bottom)
                onNodeWithContentDescription("Close").assertIsDisplayed()
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                    .assertCountEquals(0)
                runOnIdle { naturalHeight = 180f }
                assertEquals(short.height, onNodeWithTag("app-content").fetchSemanticsNode().boundsInRoot.height)
            }
        }
    }

    @Test
    fun `modal mixed column and wrapped row leave native controls visible beside a capped document`() {
        for (width in listOf(420f, 1280f)) {
            for (isRow in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width, 900f)) {
                    val children = persistentListOf(label("Header"), web, label("Footer"))
                    val root = if (isRow) {
                        UiNodeUi.Row("row", children, GapUi.Small, isWrapped = true)
                    } else {
                        UiNodeUi.Column("column", children, GapUi.Small)
                    }
                    var naturalHeight by mutableFloatStateOf(180f)
                    val document = SizedDocument { naturalHeight }
                    setContent {
                        Themed {
                            AppHostView(expanded, hasDocument = true, {}) { body ->
                                SurfaceContent(
                                    SurfaceState(root = root),
                                    {},
                                    body.testTag("app-content"),
                                    webs = persistentMapOf(web.key to document),
                                    isApp = true,
                                    isContentFill = false,
                                )
                            }
                        }
                    }
                    assertTrue(onNodeWithTag("app-content").fetchSemanticsNode().boundsInRoot.height < 350f)
                    runOnIdle { naturalHeight = 1800f }
                    val body = onNodeWithTag("app-content").fetchSemanticsNode().boundsInRoot
                    val header = onNodeWithText("Header").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val footer = onNodeWithText("Footer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val viewport = onNodeWithTag("document").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue(body.height < 900f)
                    assertTrue(header.top >= body.top && footer.bottom <= body.bottom)
                    assertTrue(viewport.height > 0f && viewport.bottom <= body.bottom)
                    assertControlsAroundDocument(header, viewport, footer, isVertical = !isRow || width == 420f)
                    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                        .assertCountEquals(0)
                }
            }
        }
    }

    @Test
    fun `native modal follows short content and uses one scroll only when its body exceeds the viewport`() =
        runSkikoComposeUiTest(size = Size(1280f, 900f)) {
            val short = UiNodeUi.Column("root", persistentListOf(label("Summary")), GapUi.Small)
            var root by mutableStateOf(short)
            val scroll = ScrollState(0)
            setContent {
                Themed {
                    AppHostView(expanded, hasDocument = false, {}) { body ->
                        SurfaceContent(
                            SurfaceState(root = root),
                            {},
                            body.testTag("app-content"),
                            isApp = true,
                            scrollState = scroll,
                            isContentFill = false,
                        )
                    }
                }
            }
            val initialHeight = onNodeWithTag("app-content").fetchSemanticsNode().boundsInRoot.height
            assertTrue(initialHeight < 100f)
            assertEquals(0, scroll.maxValue)
            runOnIdle { root = short.copy(children = (0..80).map { label("Row $it") }.toImmutableList()) }
            val longHeight = onNodeWithTag("app-content").fetchSemanticsNode().boundsInRoot.height
            assertTrue(longHeight > 700f && longHeight < 900f)
            assertTrue(scroll.maxValue > 0)
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(1)
            onNodeWithContentDescription("Close").assertIsDisplayed()
            runOnIdle { root = short }
            assertEquals(initialHeight, onNodeWithTag("app-content").fetchSemanticsNode().boundsInRoot.height)
            assertEquals(0, scroll.maxValue)
        }

    @Test
    fun `compact chat card opens once and never mounts the document`() = runSkikoComposeUiTest(
        size = Size(420f, 480f),
    ) {
        var opens = 0
        setContent {
            Themed {
                CardView(
                    CardState(phase = CardPhase.Drawn, title = "Orbit Gym", isApp = true, isOpenEnabled = true),
                    persistentMapOf(),
                    onOpenApp = { opens++ },
                )
            }
        }
        onNodeWithText("Orbit Gym").assertIsDisplayed()
        onNodeWithText("Open app").performClick()
        assertEquals(1, opens)
        assertTrue(onNodeWithText("Open app").fetchSemanticsNode().boundsInRoot.bottom < 100f)
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
    }

    @Test
    fun `start and modal show one body with reachable expand and close`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        var state by mutableStateOf(AppHostState(title = "Orbit Gym", isStart = true, sequence = 7))
        val intents = mutableListOf<AppHostIntent>()
        setContent {
            Themed {
                AppHostView(state, hasDocument = true, { intents += it }, isDocumentOnly = true) { body ->
                    SizedDocument { 180f }.Content(body)
                }
            }
        }
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "document")).assertCountEquals(1)
        assertTrue(onNodeWithTag("document").fetchSemanticsNode().boundsInRoot.height > 700f)
        onNodeWithContentDescription("Expand app").performClick()
        assertEquals(AppHostIntent.Open, intents.last())
        state = state.copy(isExpanded = true)
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "document")).assertCountEquals(1)
        onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        assertEquals(AppHostIntent.Close(7), intents.last())
        val bounds = onNodeWithTag("document").fetchSemanticsNode().boundsInRoot
        assertEquals(180f, bounds.height)
    }

    @Composable
    private fun Themed(dark: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop) { content() }
        }
    }

    private fun label(text: String) = UiNodeUi.Text(text, text, TextStyleUi.Body, ToneUi.Neutral)

    private fun assertControlsAroundDocument(header: Rect, document: Rect, footer: Rect, isVertical: Boolean) {
        if (isVertical) {
            assertTrue(header.bottom <= document.top && document.bottom <= footer.top)
        } else {
            assertTrue(header.right <= document.left && document.right <= footer.left)
        }
    }

    private val web = UiNodeUi.Web("document", "<p>App</p>", false, null)
    private val expanded = AppHostState(title = "Orbit Gym", isExpanded = true, sequence = 7)

    private class SizedDocument(private val naturalHeight: () -> Float) : ComposableComponent {
        @Composable
        override fun Content(modifier: Modifier) {
            Box(modifier.height(naturalHeight().dp).fillMaxWidth().testTag("document"))
        }
    }

    private object TestDocument : ComposableComponent {
        @Composable
        override fun Content(modifier: Modifier) {
            Box(modifier.testTag("document"))
        }
    }
}
