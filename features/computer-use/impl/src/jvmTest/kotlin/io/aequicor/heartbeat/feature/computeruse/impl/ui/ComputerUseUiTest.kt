package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.BlockerUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenIntent
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenState
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.SettingsError
import io.aequicor.heartbeat.feature.computeruse.impl.resources.Res
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_load_failed
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_permission_accessibility
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_permission_open
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_permission_screen_recording
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_save_failed
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
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
    fun `missing permissions offer buttons that open their settings`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            val intents = mutableListOf<ComputerUseScreenIntent>()
            var openLabel = ""
            var screenRecordingTitle = ""
            var accessibilityTitle = ""
            setContent {
                HbTheme {
                    openLabel = stringResource(Res.string.computer_use_permission_open)
                    screenRecordingTitle = stringResource(Res.string.computer_use_permission_screen_recording)
                    accessibilityTitle = stringResource(Res.string.computer_use_permission_accessibility)
                    ComputerUseContent(
                        ComputerUseScreenState(
                            isEnabled = true,
                            isLoaded = true,
                            blockers = persistentListOf(
                                BlockerUi.ScreenRecordingPermission,
                                BlockerUi.AccessibilityPermission,
                            ),
                        ),
                        intents::add,
                        null,
                    )
                }
            }
            onNodeWithTag("computer-use-permissions")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            onNodeWithTag("computer-use-permission-ScreenRecordingPermission")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            onNodeWithTag("computer-use-permission-AccessibilityPermission")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            onNodeWithTag("computer-use-blockers").assertDoesNotExist()
            onAllNodes(hasClickAction()).assertCountEquals(3)
            onNodeWithTag("computer-use-grant-ScreenRecordingPermission")
                .assertContentDescriptionEquals("$openLabel: $screenRecordingTitle").performClick()
            onNodeWithTag("computer-use-grant-AccessibilityPermission")
                .assertContentDescriptionEquals("$openLabel: $accessibilityTitle").performClick()
            runOnIdle {
                assertEquals(
                    listOf<ComputerUseScreenIntent>(
                        ComputerUseScreenIntent.GrantPermission(BlockerUi.ScreenRecordingPermission),
                        ComputerUseScreenIntent.GrantPermission(BlockerUi.AccessibilityPermission),
                    ),
                    intents,
                )
            }
        }

    @Test
    fun `granting another permission preserves keyboard focus on the remaining permission`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            val state = mutableStateOf(
                ComputerUseScreenState(
                    isEnabled = true,
                    isLoaded = true,
                    blockers = persistentListOf(
                        BlockerUi.ScreenRecordingPermission,
                        BlockerUi.AccessibilityPermission,
                    ),
                ),
            )
            setContent { HbTheme { ComputerUseContent(state.value, {}, null) } }
            onNodeWithTag("computer-use").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("computer-use-enabled").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("computer-use-grant-ScreenRecordingPermission")
                .assertIsFocused().performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("computer-use-grant-AccessibilityPermission").assertIsFocused()
            runOnIdle {
                state.value = state.value.copy(blockers = persistentListOf(BlockerUi.AccessibilityPermission))
            }
            onNodeWithTag("computer-use-grant-ScreenRecordingPermission").assertDoesNotExist()
            onNodeWithTag("computer-use-grant-AccessibilityPermission").assertIsFocused()
        }

    @Test
    fun `permissions stay hidden while the tool is off`() = runSkikoComposeUiTest(size = Size(420f, 800f)) {
        setContent {
            HbTheme {
                ComputerUseContent(
                    ComputerUseScreenState(
                        isLoaded = true,
                        blockers = persistentListOf(BlockerUi.ScreenRecordingPermission),
                    ),
                    {},
                    null,
                )
            }
        }
        onNodeWithTag("computer-use-permissions").assertDoesNotExist()
        onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `other host blockers add no controls and switch is reachable by keyboard`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            val intents = mutableListOf<ComputerUseScreenIntent>()
            setContent {
                HbTheme {
                    ComputerUseContent(
                        ComputerUseScreenState(
                            isEnabled = true,
                            isLoaded = true,
                            blockers = persistentListOf(BlockerUi.Headless),
                        ),
                        intents::add,
                        null,
                    )
                }
            }
            onNodeWithTag("computer-use-blockers")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            onNodeWithTag("computer-use-permissions").assertDoesNotExist()
            onAllNodes(hasClickAction()).assertCountEquals(1)
            onNodeWithTag("computer-use").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("computer-use-enabled").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
            runOnIdle {
                assertEquals(listOf<ComputerUseScreenIntent>(ComputerUseScreenIntent.SetEnabled(false)), intents)
            }
        }

    @Test
    fun `load failure keeps the switch locked and explains the failure`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            var loadFailed = ""
            var saveFailed = ""
            setContent {
                HbTheme {
                    loadFailed = stringResource(Res.string.computer_use_load_failed)
                    saveFailed = stringResource(Res.string.computer_use_save_failed)
                    ComputerUseContent(ComputerUseScreenState(error = SettingsError.LoadFailed), {}, null)
                }
            }
            onNodeWithTag("computer-use-enabled").assertIsNotEnabled()
            onNodeWithTag("computer-use-error")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            onNodeWithText(loadFailed).assertIsDisplayed()
            onNodeWithText(saveFailed).assertDoesNotExist()
        }

    @Test
    fun `save failure is reported below the switch`() = runSkikoComposeUiTest(size = Size(420f, 800f)) {
        var saveFailed = ""
        setContent {
            HbTheme {
                saveFailed = stringResource(Res.string.computer_use_save_failed)
                ComputerUseContent(ComputerUseScreenState(isLoaded = true, error = SettingsError.SaveFailed), {}, null)
            }
        }
        onNodeWithTag("computer-use-enabled").assertIsEnabled()
        onNodeWithText(saveFailed).assertIsDisplayed()
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
                                    ComputerUseScreenState(
                                        isEnabled = isDark,
                                        isLoaded = true,
                                        blockers = persistentListOf(
                                            BlockerUi.ScreenRecordingPermission,
                                            BlockerUi.AccessibilityPermission,
                                        ),
                                        error = SettingsError.SaveFailed.takeIf { isDark },
                                    ),
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
