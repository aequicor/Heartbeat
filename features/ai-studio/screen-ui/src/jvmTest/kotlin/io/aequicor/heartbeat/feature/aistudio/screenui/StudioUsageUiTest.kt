package io.aequicor.heartbeat.feature.aistudio.screenui

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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_compacting
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_limit_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_tokens
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_tokens_unknown_limit
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.UsageWindowUi
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

@OptIn(ExperimentalTestApi::class)
class StudioUsageUiTest {
    @Test
    fun `unknown capacity shows tokens without a percentage and still offers manual compaction`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            val events = mutableListOf<StudioIntent>()
            var content by mutableStateOf(
                pane().copy(
                    contextUsage = ContextUsageUi(4096, null, null),
                    pane = PaneUi(0, sessionId = "s"),
                    isCompactEnabled = true,
                ),
            )
            var unknownTokens = ""
            var unknownLimit = ""
            var knownTokens = ""
            setContent {
                unknownTokens = stringResource(Res.string.usage_tokens_unknown_limit, "4096")
                unknownLimit = stringResource(Res.string.usage_limit_unknown)
                knownTokens = stringResource(Res.string.usage_tokens, "4096", "8192")
                UsageHost(content, compact = true, onIntent = events::add)
            }
            onNodeWithContentDescription(unknownTokens).assertIsDisplayed()
            onNodeWithTag("usage-0").performClick()
            onNodeWithText(unknownLimit).assertIsDisplayed()
            onNodeWithText(unknownTokens).assertIsDisplayed()
            onNodeWithText("0%").assertDoesNotExist()
            onNodeWithTag("compact-0").performClick()
            runOnIdle { assertEquals(StudioIntent.CompactContext(0), events.last()) }
            runOnIdle { content = content.copy(contextUsage = ContextUsageUi(4096, 8192, 50)) }
            onNodeWithText("50%").assertIsDisplayed()
            onNodeWithTag("usage-0").performClick()
            onNodeWithText(knownTokens).assertIsDisplayed()
            onNodeWithText(unknownLimit).assertDoesNotExist()
        }

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
    fun `a compacting chat announces it on the context indicator until it ends`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val usage = ContextUsageUi(640, 1000, 64)
            var content by mutableStateOf(pane().copy(contextUsage = usage, isCompacting = true))
            var compacting = ""
            setContent {
                compacting = stringResource(Res.string.usage_compacting)
                UsageHost(content)
            }
            onNodeWithContentDescription(compacting, substring = true).assertIsDisplayed()
            runOnIdle { content = content.copy(contextUsage = ContextUsageUi(120, 1000, 12), isCompacting = false) }
            onNodeWithContentDescription(compacting, substring = true).assertDoesNotExist()
            onNodeWithText("12%").assertIsDisplayed()
        }

    @Test
    fun `confirmed zero opens details and refreshes the selected connection`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<StudioIntent>()
            setContent { UsageHost(pane().copy(contextUsage = ContextUsageUi(0, 1000, 0)), onIntent = events::add) }
            onNodeWithText("0%").assertIsDisplayed()
            onNodeWithTag("usage-0").performClick()
            runOnIdle { assertEquals(listOf<StudioIntent>(StudioIntent.RefreshUsage("model")), events) }
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
                    var context by mutableStateOf(ContextUsageUi(78080, 128000, 61))
                    var unknownTokens = ""
                    setContent {
                        unknownTokens = stringResource(Res.string.usage_tokens_unknown_limit, "78080")
                        UsageHost(
                            pane().copy(contextUsage = context, providerUsage = provider()),
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
                    runOnIdle { context = ContextUsageUi(78080, null, null) }
                    onNodeWithText(unknownTokens).assertIsDisplayed()
                    check(
                        ImageIO.write(
                            captureToImage().toAwtImage(),
                            "png",
                            File(directory, "usage-unknown-${width.toInt()}-$dark.png"),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `usage refresh uses confirmed pane settings while session metadata still names the old model`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<StudioIntent>()
            val base = pane()
            val content = base.copy(
                session = SessionUi("s", "Chat", null, Instant.fromEpochSeconds(1), modelId = "old-model"),
                settings = base.settings.copy(modelId = "confirmed-model"),
                models = persistentListOf(ModelUi("confirmed-model", "Confirmed")),
                contextUsage = ContextUsageUi(42, 1000, 4),
            )
            setContent { UsageHost(content, onIntent = events::add) }
            onNodeWithTag("usage-0").performClick()
            runOnIdle {
                assertEquals(
                    listOf<StudioIntent>(StudioIntent.RefreshUsage("confirmed-model")),
                    events,
                )
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
    onIntent: (StudioIntent) -> Unit = {},
) {
    HbTheme(darkTheme = dark, dimensions = if (compact) HbDimensions.Mobile else HbDimensions.Desktop) {
        Box(Modifier.fillMaxSize().padding(HbTheme.spacing.xl), contentAlignment = Alignment.BottomCenter) {
            StudioComposer(content, onIntent, isCompact = compact)
        }
    }
}
