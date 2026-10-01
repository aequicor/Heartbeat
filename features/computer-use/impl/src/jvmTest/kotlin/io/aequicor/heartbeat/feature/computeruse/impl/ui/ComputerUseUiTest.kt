package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.BlockerUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenIntent
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenState
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ComputerUseUiTest {
    @Test
    fun `embedded settings contain only an enable switch and forward both values`() =
        runSkikoComposeUiTest(size = Size(900f, 800f)) {
            val intents = mutableListOf<ComputerUseScreenIntent>()
            val state = mutableStateOf(ComputerUseScreenState(isLoaded = true))
            setContent {
                HbTheme {
                    ComputerUseContent(
                        state.value,
                        { intent ->
                            intents += intent
                            state.value = state.value.copy(
                                isEnabled = (intent as ComputerUseScreenIntent.SetEnabled).isEnabled,
                            )
                        },
                        null,
                    )
                }
            }
            onNodeWithTag("computer-use-back").assertDoesNotExist()
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(1)
            onAllNodes(hasClickAction()).assertCountEquals(1)
            val switch = onNodeWithTag("computer-use-enabled")
            switch.assertIsEnabled().assertIsOff().performClick()
            switch.assertIsOn().performClick()
            switch.assertIsOff()
            runOnIdle {
                assertEquals(
                    listOf<ComputerUseScreenIntent>(
                        ComputerUseScreenIntent.SetEnabled(true),
                        ComputerUseScreenIntent.SetEnabled(false),
                    ),
                    intents,
                )
            }
        }

    @Test
    fun `switch waits for the saved preference to load`() = runSkikoComposeUiTest(size = Size(420f, 800f)) {
        setContent { HbTheme { ComputerUseContent(ComputerUseScreenState(), {}, null) } }
        onNodeWithTag("computer-use-enabled").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `permission help adds no controls and switch is reachable by keyboard`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            val intents = mutableListOf<ComputerUseScreenIntent>()
            setContent {
                HbTheme {
                    ComputerUseContent(
                        ComputerUseScreenState(
                            isEnabled = true,
                            isLoaded = true,
                            blockers = persistentListOf(BlockerUi.ScreenRecordingPermission),
                        ),
                        intents::add,
                        null,
                    )
                }
            }
            onNodeWithTag("computer-use-permissions").assertIsDisplayed()
            onAllNodes(hasClickAction()).assertCountEquals(1)
            onNodeWithTag("computer-use").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("computer-use-enabled").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
            runOnIdle {
                assertEquals(listOf<ComputerUseScreenIntent>(ComputerUseScreenIntent.SetEnabled(false)), intents)
            }
        }

    @Test
    fun `renders desktop and compact settings in both themes`() {
        for (width in listOf(1280, 420)) {
            for (isDark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(darkTheme = isDark) {
                                ComputerUseContent(
                                    ComputerUseScreenState(isEnabled = isDark, isLoaded = true),
                                    {},
                                    null,
                                )
                            }
                        }
                    }
                    onNodeWithTag("computer-use-enabled").assertIsDisplayed()
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
