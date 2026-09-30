package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.ds.tokens.contrastRatio
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbControlsInteractionTest {
    @Test
    fun `hover changes pixels and leaving restores the exact idle surface`() = runSkikoComposeUiTest(
        size = Size(420f, 240f),
    ) {
        setContent { InteractionHost(onClick = {}) }
        val idle = captureToImage().toAwtImage()
        saveInteractionPreview("idle", idle)
        onNodeWithTag("action").performMouseInput { moveTo(center) }
        val hovered = captureToImage().toAwtImage()
        saveInteractionPreview("hover", hovered)
        assertFalse(samePixels(idle, hovered), "Hover must visibly change the surface")
        onNodeWithTag("action").performMouseInput { exit() }
        val restored = captureToImage().toAwtImage()
        assertTrue(samePixels(idle, restored), "Leaving must restore the original surface")
    }

    @Test
    fun `held press is visible and release fires exactly once`() = runSkikoComposeUiTest(size = Size(420f, 240f)) {
        var clicks = 0
        setContent { InteractionHost(onClick = { clicks++ }) }
        onNodeWithTag("action").performMouseInput { moveTo(center) }
        val hovered = captureToImage().toAwtImage()
        onNodeWithTag("action").performMouseInput { press() }
        val pressed = captureToImage().toAwtImage()
        saveInteractionPreview("pressed", pressed)
        assertFalse(samePixels(hovered, pressed), "Holding the pointer must show the inset state")
        runOnIdle { assertEquals(0, clicks) }
        onNodeWithTag("action").performMouseInput { release() }
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `dragging outside cancels activation and disabled control cannot activate`() = runSkikoComposeUiTest(
        size = Size(420f, 240f),
    ) {
        var clicks = 0
        var isEnabled by mutableStateOf(true)
        setContent { InteractionHost(onClick = { clicks++ }, isEnabled = isEnabled) }
        onNodeWithTag("action").performMouseInput {
            moveTo(center)
            press()
        }
        waitForIdle()
        onNodeWithTag("action").performMouseInput {
            moveTo(Offset(width + 40f, centerY))
            release()
        }
        runOnIdle {
            assertEquals(0, clicks, "Releasing outside must cancel the action")
            isEnabled = false
        }
        onNodeWithTag("action").assertIsNotEnabled()
        onNodeWithTag("action").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        runOnIdle { assertEquals(0, clicks) }
        saveInteractionPreview("disabled", captureToImage().toAwtImage())
    }

    @Test
    fun `keyboard focus is visible and Enter and Space activate once each`() = runSkikoComposeUiTest(
        size = Size(420f, 240f),
    ) {
        var clicks = 0
        setContent { InteractionHost(onClick = { clicks++ }) }
        val idle = captureToImage().toAwtImage()
        onNodeWithTag("action").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("action").assertIsFocused()
        val focused = captureToImage().toAwtImage()
        saveInteractionPreview("focus", focused)
        assertFalse(samePixels(idle, focused), "Keyboard focus must have a visible indicator")
        onNodeWithTag("action").performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, clicks) }
        onNodeWithTag("action").performKeyInput { pressKey(Key.Spacebar) }
        runOnIdle { assertEquals(2, clicks) }
    }

    @Test
    fun `reduced motion reaches hover appearance without lingering animation`() = runSkikoComposeUiTest(
        size = Size(420f, 240f),
    ) {
        setContent { InteractionHost(onClick = {}, isReducedMotion = true) }
        val idle = captureToImage().toAwtImage()
        mainClock.autoAdvance = false
        onNodeWithTag("action").performMouseInput { moveTo(center) }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val immediate = captureToImage().toAwtImage()
        mainClock.advanceTimeBy(500)
        val settled = captureToImage().toAwtImage()
        assertFalse(samePixels(idle, immediate), "Reduced motion still needs immediate hover feedback")
        assertTrue(samePixels(immediate, settled), "Reduced motion must not interpolate after rendering")
        saveInteractionPreview("reduced-motion-hover", settled)
    }

    @Test
    fun `hover animation can reverse before finishing without leaving stale tint`() = runSkikoComposeUiTest(
        size = Size(420f, 240f),
    ) {
        setContent { InteractionHost(onClick = {}) }
        val idle = captureToImage().toAwtImage()
        mainClock.autoAdvance = false
        onNodeWithTag("action").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(48)
        onNodeWithTag("action").performMouseInput { exit() }
        mainClock.advanceTimeBy(500)
        assertTrue(samePixels(idle, captureToImage().toAwtImage()))
    }

    @Test
    fun `field keeps its accessible name after placeholder disappears`() = runSkikoComposeUiTest(
        size = Size(420f, 240f),
    ) {
        var text by mutableStateOf("")
        setContent {
            HbTheme(darkTheme = false) {
                HbTextField(text, { text = it }, placeholder = "Message the agent", accessibleLabel = "Agent prompt")
            }
        }
        onNodeWithContentDescription("Agent prompt").performTextInput("Keep this name")
        onNodeWithContentDescription("Agent prompt").assertIsFocused()
        runOnIdle { assertEquals("Keep this name", text) }
    }

    @Test
    fun `palette changes update button fill immediately and preserve keyboard focus`() = runSkikoComposeUiTest(
        size = Size(420f, 240f),
    ) {
        var isDark by mutableStateOf(false)
        setContent { InteractionHost(onClick = {}, isDark = isDark) }
        onNodeWithTag("action").performSemanticsAction(SemanticsActions.RequestFocus)
        mainClock.autoAdvance = false
        listOf(true, false).forEach { nextTheme ->
            runOnIdle { isDark = nextTheme }
            mainClock.advanceTimeByFrame()
            onNodeWithTag("action").assertIsFocused()
            val bounds = onNodeWithTag("action").fetchSemanticsNode().boundsInRoot
            val image = captureToImage().toAwtImage()
            val pixel = Color(image.getRGB(bounds.center.x.toInt(), (bounds.top + bounds.height / 4f).toInt()))
            val colors = if (nextTheme) HbColors.DesktopDark else HbColors.DesktopLight
            assertEquals(colors.buttonFill.toArgb(), pixel.toArgb(), "First theme frame must use the new fill")
            assertTrue(contrastRatio(colors.textPrimary, pixel) >= 4.5f, "Theme transition must preserve text contrast")
            saveInteractionPreview("theme-${if (nextTheme) "dark" else "light"}-first-frame", image)
        }
    }

    @Test
    fun `dynamic button styles keep first frame contrast and focus in the dark theme`() = runSkikoComposeUiTest(
        size = Size(420f, 240f),
    ) {
        var style by mutableStateOf(HbButtonStyle.Primary)
        setContent { InteractionHost(onClick = {}, isDark = true, style = style) }
        onNodeWithTag("action").performSemanticsAction(SemanticsActions.RequestFocus)
        mainClock.autoAdvance = false
        listOf(HbButtonStyle.Ghost, HbButtonStyle.Primary, HbButtonStyle.Secondary, HbButtonStyle.Primary).forEach {
            runOnIdle { style = it }
            mainClock.advanceTimeByFrame()
            onNodeWithTag("action").assertIsFocused()
            val bounds = onNodeWithTag("action").fetchSemanticsNode().boundsInRoot
            val image = captureToImage().toAwtImage()
            val pixel = Color(image.getRGB(bounds.center.x.toInt(), (bounds.top + bounds.height / 4f).toInt()))
            val colors = HbColors.DesktopDark
            val foreground = if (it == HbButtonStyle.Primary) colors.onPrimary else colors.textPrimary
            val expectedFill = when (it) {
                HbButtonStyle.Primary -> colors.primary
                HbButtonStyle.Ghost -> colors.background
                HbButtonStyle.Secondary, HbButtonStyle.Danger -> colors.buttonFill
            }
            assertEquals(expectedFill.toArgb(), pixel.toArgb(), "First style frame must use its matching fill")
            assertTrue(contrastRatio(foreground, pixel) >= 4.5f, "Style transition must preserve text contrast")
            saveInteractionPreview("style-${it.name.lowercase()}-first-frame", image)
        }
    }
}

@Composable
private fun InteractionHost(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    isReducedMotion: Boolean = false,
    isDark: Boolean = false,
    style: HbButtonStyle = HbButtonStyle.Secondary,
) {
    HbTheme(darkTheme = isDark, motion = HbMotion(isReducedMotion = isReducedMotion)) {
        Box(
            modifier = modifier.fillMaxSize().background(HbTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            HbButton(
                text = "Action",
                onClick = onClick,
                modifier = Modifier.testTag("action"),
                style = style,
                enabled = isEnabled,
            )
        }
    }
}

private fun samePixels(first: BufferedImage, second: BufferedImage): Boolean =
    first.getRGB(0, 0, first.width, first.height, null, 0, first.width).contentEquals(
        second.getRGB(0, 0, second.width, second.height, null, 0, second.width),
    )

private fun saveInteractionPreview(name: String, image: BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
    check(ImageIO.write(image, "png", File(directory, "interaction-$name.png"))) { "PNG encoder is unavailable" }
}
