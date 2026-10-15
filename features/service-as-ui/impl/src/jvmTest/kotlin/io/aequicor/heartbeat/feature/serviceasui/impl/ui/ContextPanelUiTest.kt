package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.GapUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.TextStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ToneUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.WebHeightUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.LauncherUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ContextPanelUiTest {
    @Test
    fun `native context follows its content height and renders one heading in both themes`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(420f, 780f)) {
                setContent {
                    Themed(dark) {
                        ContextPanelFrame(Modifier.width(HbTheme.dimensions.inspectorPanelWidth).testTag("panel")) {
                            SurfaceContent(
                                SurfaceState(root = referenceBody),
                                {},
                                Modifier.weight(1f, fill = false),
                                isApp = true,
                                isContentFill = false,
                                isBodyInset = true,
                            )
                        }
                    }
                }
                val bounds = onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.height in 200f..500f, "A short panel must not consume the available screen height")
                onNodeWithText("Heartbeat").assertIsDisplayed()
                onAllNodes(androidx.compose.ui.test.hasText("Heartbeat")).assertCountEquals(1)
                onNodeWithText("Источники").assertIsDisplayed()
                val output = File("build/previews/context-panel-${if (dark) "dark" else "light"}.png")
                output.parentFile.mkdirs()
                check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output))
            }
        }
    }

    @Test
    fun `long native context has one scroll and its final row remains reachable`() = runSkikoComposeUiTest(
        size = Size(360f, 420f),
    ) {
        setContent {
            Themed {
                ContextPanelFrame(Modifier.testTag("panel")) {
                    SurfaceContent(
                        SurfaceState(
                            root = UiNodeUi.Column(
                                "long",
                                (0..60).map { label("Row $it") }.toImmutableList(),
                                GapUi.Medium,
                            ),
                        ),
                        {},
                        Modifier.weight(1f, fill = false),
                        isApp = true,
                        isContentFill = false,
                        isBodyInset = true,
                    )
                }
            }
        }
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(1)
        assertTrue(onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot.bottom <= 420f)
        onNodeWithText("Row 60").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `web context keeps the card inset and resizes within its bound without an outer scroll`() =
        runSkikoComposeUiTest(
            size = Size(360f, 420f),
        ) {
            var height by mutableStateOf(120.dp)
            val web = UiNodeUi.Web("web", "<main>Context</main>", false, null, WebHeightUi.Content)
            val document = object : ComposableComponent {
                @Composable
                override fun Content(modifier: Modifier) {
                    Box(modifier.height(height).fillMaxWidth().testTag("web"))
                }
            }
            setContent {
                Themed {
                    ContextPanelFrame(Modifier.testTag("panel")) {
                        SurfaceContent(
                            SurfaceState(root = web),
                            {},
                            Modifier.weight(1f, fill = false),
                            webs = persistentMapOf("web" to document),
                            isApp = true,
                            isContentFill = false,
                            isBodyInset = true,
                        )
                    }
                }
            }
            assertEquals(152f, onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot.height)
            assertEquals(16f, onNodeWithTag("web").fetchSemanticsNode().boundsInRoot.left)
            height = 1000.dp
            waitForIdle()
            assertEquals(420f, onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot.height)
            height = 80.dp
            waitForIdle()
            assertEquals(112f, onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot.height)
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
        }

    @Test
    fun `context launcher menu opens its selected surface without a dock header`() {
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            runSkikoComposeUiTest(size = Size(360f, 420f)) {
                val intents = mutableListOf<PlaceIntent>()
                setContent {
                    Themed {
                        PlaceView(
                            PlaceState(launchers = persistentListOf(LauncherUi("details", "Details"))),
                            persistentMapOf(),
                            { intents += it },
                            false,
                            isContext = true,
                        )
                    }
                }
                onNodeWithContentDescription("Actions").performClick()
                onNodeWithText("Details").performClick()
                assertEquals(listOf<PlaceIntent>(PlaceIntent.Launch("details")), intents)
                onNodeWithContentDescription("Close the panel").assertDoesNotExist()
            }
        } finally {
            Locale.setDefault(locale)
        }
    }

    @Composable
    private fun Themed(dark: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop, content = content)
        }
    }

    private fun label(text: String, style: TextStyleUi = TextStyleUi.Body, tone: ToneUi = ToneUi.Neutral) =
        UiNodeUi.Text(text, text, style, tone)

    private val referenceBody = UiNodeUi.Column(
        "context",
        persistentListOf(
            label("Heartbeat", TextStyleUi.Caption),
            UiNodeUi.Row(
                "changes",
                persistentListOf(label("Изменения"), label("+3 830 −200", tone = ToneUi.Success)),
                GapUi.Small,
                false,
            ),
            label("feat(release): Linux desktop and services"),
            UiNodeUi.Divider("first"),
            label("Запросы на включение изменений", TextStyleUi.Caption),
            label("fix(app-update): устранить обход"),
            UiNodeUi.Divider("second"),
            label("Субагенты", TextStyleUi.Caption),
            label("1 в работе · Готово: 5"),
            UiNodeUi.Divider("third"),
            label("Источники", TextStyleUi.Caption),
            label("github.com/aequicor/Heartbeat"),
            label("ChatGPT App Tools"),
        ),
        GapUi.Large,
    )
}
