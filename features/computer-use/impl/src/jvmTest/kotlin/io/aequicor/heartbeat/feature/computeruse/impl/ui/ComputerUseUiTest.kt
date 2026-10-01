package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenIntent
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenState
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ModeUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.PhaseUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.WindowRowUi
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ComputerUseUiTest {
    @Test
    fun `idle panel exposes recovery without a back header when embedded`() =
        runSkikoComposeUiTest(size = Size(900f, 800f)) {
            val intents = mutableListOf<ComputerUseScreenIntent>()
            setContent {
                HbTheme { ComputerUseContent(ComputerUseScreenState(), intents::add, null) }
            }
            onNodeWithTag("computer-use-back").assertDoesNotExist()
            onNodeWithTag("computer-use-retry").assertIsEnabled().performClick()
            runOnIdle { assertEquals(listOf<ComputerUseScreenIntent>(ComputerUseScreenIntent.Retry), intents) }
        }

    @Test
    fun `window picker announces its selected target and forwards a new selection`() =
        runSkikoComposeUiTest(size = Size(900f, 800f)) {
            val intents = mutableListOf<ComputerUseScreenIntent>()
            val state = ComputerUseScreenState(
                phase = PhaseUi.Capturing,
                mode = ModeUi.Window,
                isCaptureOpen = true,
                isWindowModeAvailable = true,
                selectedWindowId = "a",
                targets = persistentListOf(
                    WindowRowUi("a", "A", "Window A", "200 × 100", false),
                    WindowRowUi("b", "B", "Window B", "200 × 100", false),
                ),
            )
            setContent { HbTheme { ComputerUseContent(state, intents::add, null) } }
            onNodeWithTag("computer-use-select-a").performScrollTo().assertIsSelected()
            onNodeWithTag("computer-use-select-b").performScrollTo().performClick()
            onNodeWithTag("computer-use-revoke").assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(
                    listOf<ComputerUseScreenIntent>(
                        ComputerUseScreenIntent.SelectWindow("b"),
                        ComputerUseScreenIntent.Revoke,
                    ),
                    intents,
                )
            }
        }

    @Test
    fun `renders desktop and compact panels in both themes`() {
        for (width in listOf(1280, 420)) {
            for (isDark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(darkTheme = isDark) {
                                ComputerUseContent(ComputerUseScreenState(phase = PhaseUi.Ready), {}, null)
                            }
                        }
                    }
                    waitForIdle()
                    val file = File(
                        "build/reports/snapshots/computer-use-$width-${if (isDark) "dark" else "light"}.png",
                    )
                    file.parentFile.mkdirs()
                    ImageIO.write(captureToImage().toAwtImage(), "png", file)
                }
            }
        }
    }
}
