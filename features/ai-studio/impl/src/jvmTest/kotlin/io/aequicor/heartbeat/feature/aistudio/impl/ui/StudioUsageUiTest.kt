package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.UsageWindowUi
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StudioUsageUiTest {
    @Test
    fun `unknown telemetry is hidden and quota-only button never invents a zero context`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            var content by mutableStateOf(pane())
            setContent { UsageHost(content) }
            onNodeWithTag("usage-0").assertDoesNotExist()
            runOnIdle { content = content.copy(providerUsage = provider()) }
            onNodeWithTag("usage-0").assertIsDisplayed().performClick()
            onNodeWithText("Weekly").assertIsDisplayed()
            onNodeWithText("99%").assertIsDisplayed()
            onNodeWithText("0%").assertDoesNotExist()
        }

    @Test
    fun `confirmed zero opens details and refreshes the selected connection`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            setContent { UsageHost(pane().copy(contextUsage = ContextUsageUi(0, 1000, 0)), onIntent = events::add) }
            onNodeWithText("0%").assertIsDisplayed()
            onNodeWithTag("usage-0").performClick()
            runOnIdle { assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.RefreshUsage("model")), events) }
        }

    @Test
    fun `open context updates while streaming and session switch dismisses details`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            var content by mutableStateOf(
                pane().copy(
                    contextUsage = ContextUsageUi(610, 1000, 61),
                    providerUsage = provider(),
                    session = SessionUi(
                        "s",
                        "Chat",
                        null,
                        Instant.fromEpochSeconds(1),
                        isRunning = true,
                        modelId = "model",
                    ),
                ),
            )
            setContent { UsageHost(content) }
            onNodeWithTag("usage-0").performClick()
            onNodeWithText("Weekly").assertIsDisplayed()
            runOnIdle { content = content.copy(contextUsage = ContextUsageUi(1000, 1000, 100)) }
            onNodeWithText("61%").assertDoesNotExist()
            runOnIdle { content = content.copy(pane = content.pane.copy(sessionId = "another")) }
            onNodeWithText("Weekly").assertDoesNotExist()
        }

    @Test
    fun `usage details fit desktop and compact viewports in both themes`() {
        for (width in listOf(1280f, 420f)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width, 800f)) {
                    setContent {
                        UsageHost(
                            pane().copy(contextUsage = ContextUsageUi(78080, 128000, 61), providerUsage = provider()),
                            dark,
                            compact = width < 500f,
                        )
                    }
                    onNodeWithTag("usage-0").performClick()
                    onNodeWithText("Weekly").assertIsDisplayed()
                    val directory = File("build/previews").apply { mkdirs() }
                    check(
                        ImageIO.write(
                            captureToImage().toAwtImage(),
                            "png",
                            File(directory, "usage-${width.toInt()}-$dark.png"),
                        ),
                    )
                }
            }
        }
    }

    private fun pane(): PaneContent {
        val pane = PaneUi(0)
        val state = AiStudioScreenState(
            panes = persistentListOf(pane),
            models = persistentListOf(ModelUi("model", "Model")),
        )
        return state.copy(settings = state.settings.copy(modelId = "model")).paneContent(pane)
    }

    private fun provider() = ProviderUsageUi(
        persistentListOf(UsageWindowUi("week", "Weekly", 99, Instant.fromEpochSeconds(1800000000))),
        null,
        "Pro",
        Instant.fromEpochSeconds(1790000000),
        false,
    )
}

@Composable
private fun UsageHost(
    content: PaneContent,
    dark: Boolean = false,
    compact: Boolean = false,
    onIntent: (AiStudioScreenIntent) -> Unit = {},
) {
    HbTheme(darkTheme = dark, dimensions = if (compact) HbDimensions.Mobile else HbDimensions.Desktop) {
        Box(Modifier.fillMaxSize().padding(HbTheme.spacing.xl), contentAlignment = Alignment.BottomCenter) {
            StudioComposer(content, onIntent, isCompact = compact)
        }
    }
}
