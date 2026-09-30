package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class HbSettingsUiTest {
    @Test
    fun `button sizes are dense on desktop and never below the touch target on mobile`() {
        for (dimensions in listOf(HbDimensions.Desktop, HbDimensions.Mobile)) {
            runSkikoComposeUiTest(size = Size(420f, 240f)) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        HbTheme(dimensions = dimensions) {
                            Box {
                                HbButton("Regular", {}, Modifier.testTag("regular"))
                                HbButton("Small", {}, Modifier.testTag("small"), size = HbButtonSize.Small)
                            }
                        }
                    }
                }
                if (dimensions.isDesktop) {
                    onNodeWithTag("regular").assertHeightIsEqualTo(dimensions.controlHeight)
                    onNodeWithTag("small").assertHeightIsEqualTo(dimensions.compactControlHeight)
                } else {
                    onNodeWithTag("regular").assertHeightIsAtLeast(dimensions.touchTarget)
                    onNodeWithTag("small").assertHeightIsAtLeast(dimensions.touchTarget)
                }
            }
        }
    }

    @Test
    fun `settings row is one button with a quiet hover fill that disappears on exit`() = runSkikoComposeUiTest(
        size = Size(420f, 160f),
    ) {
        var clicks = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true)) {
                    Box(Modifier.fillMaxSize().background(HbTheme.colors.background)) {
                        HbSettingsRow(
                            "Длинное название настройки, которое не помещается в строку и гаснет к краю",
                            Modifier.testTag("row"),
                            description = "Описание 12sp",
                            onClick = { clicks++ },
                        )
                    }
                }
            }
        }
        val idle = captureToImage().toAwtImage().getRGB(4, 20)
        onNodeWithTag("row").performMouseInput { moveTo(center) }
        waitForIdle()
        val hovered = captureToImage().toAwtImage().getRGB(4, 20)
        assertNotEquals(idle, hovered, "Hover must fill the row")
        onNodeWithTag("row").performMouseInput { exit() }
        waitForIdle()
        assertEquals(idle, captureToImage().toAwtImage().getRGB(4, 20), "Leaving restores the idle row")
        onNodeWithTag("row").performClick()
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `disabled row does not react`() = runSkikoComposeUiTest {
        var clicks = 0
        setContent {
            HbTheme { HbSettingsRow("Title", Modifier.testTag("row"), onClick = { clicks++ }, enabled = false) }
        }
        onNodeWithTag("row").performClick()
        runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun `dialog closes with Escape and runs its actions`() = runSkikoComposeUiTest(size = Size(800f, 600f)) {
        var isOpen by mutableStateOf(true)
        var isConfirmed = false
        setContent {
            HbTheme {
                if (isOpen) {
                    HbDialog(
                        "Отключить подключение?",
                        onDismissRequest = { isOpen = false },
                        actions = {
                            HbButton("Отключить", {
                                isConfirmed = true
                                isOpen = false
                            }, Modifier.testTag("confirm"), style = HbButtonStyle.Danger)
                        },
                    ) { HbText("Тело") }
                }
            }
        }
        onNodeWithText("Отключить подключение?").performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(false, isOpen) }
        runOnIdle { isOpen = true }
        onNodeWithTag("confirm").performClick()
        runOnIdle {
            assertEquals(true, isConfirmed)
            assertEquals(false, isOpen)
        }
    }
}
