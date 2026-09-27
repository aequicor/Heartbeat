package io.aequicor.heartbeat.ds.catalog

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.theme.HbVisualStyle
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.contrastRatio
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
internal class SandboxInteractionTest {
    @Test
    fun `theme switch snaps navigation background before the new text color is drawn`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        setContent { UIKitSandboxApp(initialDarkTheme = false) }
        onNodeWithText("EN").performClick()
        onNodeWithText("Light").assertIsDisplayed()
        mainClock.autoAdvance = false
        try {
            onNodeWithText("Light").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithText("Dark").assertIsDisplayed()
            val navigation = onNodeWithText("Chat playground").captureToImage().toPixelMap()
            val background = navigation[navigation.width - 8, navigation.height / 2]
            assertTrue(
                contrastRatio(HbColors.Dark.textPrimary, background) >= 4.5f,
                "The first dark-theme frame must not keep the animated light background behind light text.",
            )
        } finally {
            mainClock.autoAdvance = true
        }
    }

    @Test
    fun `chat status follows active generation and cancellation`() = runSkikoComposeUiTest(size = Size(1280f, 900f)) {
        setContent { UIKitSandboxApp(initialDarkTheme = false) }
        onNodeWithText("EN").performClick()
        onNodeWithTag("chat-status").assertTextEquals("Ready")
        onNode(hasSetTextAction()).performTextInput("A local streaming example")
        mainClock.autoAdvance = false
        try {
            onNodeWithContentDescription("Send").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithContentDescription("Stop").assertIsDisplayed()
            onNodeWithTag("chat-status").assertTextEquals("Working")
            onNodeWithContentDescription("Stop").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithTag("chat-status").assertTextEquals("Ready")
        } finally {
            mainClock.autoAdvance = true
        }
    }

    @Test
    fun `wide short viewport keeps native platform selection accessible`() = runSkikoComposeUiTest(
        size = Size(900f, 500f),
    ) {
        setContent {
            UIKitSandboxApp(
                initialPlatformUi = PlatformUi.Material,
                initialDarkTheme = false,
                initialVisualStyle = HbVisualStyle.Platform,
            )
        }
        onNodeWithText("Material").assertIsDisplayed().performClick()
        onNodeWithText("Fluent").assertIsDisplayed().performClick()
        onNodeWithText("macOS").assertIsDisplayed()
    }

    @Test
    fun `navigation hover changes appearance without selecting and keyboard activates focused destination`() =
        runSkikoComposeUiTest(size = Size(1280f, 900f)) {
            setContent { UIKitSandboxApp(initialDarkTheme = false) }
            onNodeWithText("EN").performClick()
            val destination = onNodeWithText("Components")
            destination.assertIsNotSelected()
            val before = destination.captureToImage().toPixelMap()

            destination.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(300)
            waitForIdle()
            val hovered = destination.captureToImage().toPixelMap()
            val sampleX = before.width - 8
            val sampleY = before.height / 2
            assertNotEquals(
                before[sampleX, sampleY],
                hovered[sampleX, sampleY],
                "An unselected navigation destination should respond visibly to hover.",
            )
            destination.assertIsNotSelected()
            onNodeWithText("Chat playground").assertIsSelected()

            destination.performMouseInput { exit() }
            destination.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            destination.assertIsFocused()
            destination.performKeyInput { pressKey(Key.Enter) }
            destination.assertIsSelected()
            onNodeWithText("Create something").assertIsDisplayed()
        }
}
