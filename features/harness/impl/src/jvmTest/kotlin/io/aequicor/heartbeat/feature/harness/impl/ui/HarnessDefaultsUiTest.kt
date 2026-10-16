package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailState
import io.aequicor.heartbeat.feature.harness.impl.presentation.PhaseUi
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HarnessDefaultsUiTest {
    @Test
    fun `default setting remains visible and keyboard accessible across widths and themes`() {
        for (width in listOf(1280, 420)) {
            for (isDark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
                    val intents = mutableListOf<HarnessDetailIntent>()
                    val state = HarnessDetailState(
                        phase = PhaseUi.Ready,
                        name = "compose_ui",
                        title = "Compose UI",
                        description = "Native Compose screens with the design system",
                        isEnabled = true,
                    )
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(darkTheme = isDark) { HarnessDetailContent(state, intents::add, {}) }
                        }
                    }
                    onNodeWithTag("harness-detail").performKeyInput { pressKey(Key.Tab) }
                    onNodeWithTag("harness-detail-back").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
                    onNodeWithTag("harness-detail-enabled").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
                    onNodeWithTag("harness-detail-default-new-sessions")
                        .assertIsDisplayed().assertIsOff().assertIsFocused()
                        .performKeyInput { pressKey(Key.Spacebar) }
                    runOnIdle {
                        assertEquals(
                            listOf<HarnessDetailIntent>(HarnessDetailIntent.SetDefaultForNewSessions(true)),
                            intents,
                        )
                    }
                    val theme = if (isDark) "dark" else "light"
                    val file = File("build/reports/snapshots/harness-defaults-$width-$theme.png")
                    file.parentFile.mkdirs()
                    assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", file))
                }
            }
        }
    }
}
