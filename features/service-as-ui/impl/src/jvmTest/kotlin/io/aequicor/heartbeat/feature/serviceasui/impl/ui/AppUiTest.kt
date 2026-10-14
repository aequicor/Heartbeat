package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                AppHostView(state, hasDocument = true, { intents += it }) {
                    TestDocument.Content(Modifier.fillMaxSize())
                }
            }
        }
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "document")).assertCountEquals(1)
        onNodeWithContentDescription("Expand app").performClick()
        assertEquals(AppHostIntent.Open, intents.last())
        state = state.copy(isExpanded = true)
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "document")).assertCountEquals(1)
        onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        assertEquals(AppHostIntent.Close(7), intents.last())
        val bounds = onNodeWithTag("document").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height > 700f)
    }

    @Composable
    private fun Themed(dark: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop) { content() }
        }
    }

    private fun label(text: String) = UiNodeUi.Text(text, text, TextStyleUi.Body, ToneUi.Neutral)

    private val web = UiNodeUi.Web("document", "<p>App</p>", false, null)

    private object TestDocument : ComposableComponent {
        @Composable
        override fun Content(modifier: Modifier) {
            Box(modifier.testTag("document"))
        }
    }
}
