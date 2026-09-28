package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.HbVisualStyle
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbMotion
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class HbDesktopStatesUiTest {
    @Test
    fun `all kits show an outer keyboard ring and hide it for pointer focus`() {
        focusCases().forEach { case ->
            runSkikoComposeUiTest(size = Size(420f, 260f)) {
                setContent { FocusHost(case) }
                // Fluent exposes its native click/focus target below the public layout modifier.
                val button = onNode(
                    hasClickAction() and (hasTestTag("button") or hasAnyAncestor(hasTestTag("button"))),
                )
                button.performMouseInput {
                    moveTo(center)
                    press()
                    release()
                    exit()
                }
                button.requestFocus()
                button.assertIsFocused()
                val bounds = onNodeWithTag("button").fetchSemanticsNode().boundsInRoot
                val x = bounds.center.x.toInt()
                val y = (bounds.top - 2).toInt()
                val background = case.colors.background.toArgb()
                assertEquals(background, captureToImage().toAwtImage().getRGB(x, y), "$case mouse ring")
                button.performKeyInput { pressKey(Key.Tab) }
                onNodeWithTag("field").performKeyInput {
                    keyDown(Key.ShiftLeft)
                    pressKey(Key.Tab)
                    keyUp(Key.ShiftLeft)
                }
                button.assertIsFocused()
                val keyboardImage = captureToImage().toAwtImage()
                saveFocusFrame(case, keyboardImage)
                val keyboardPixel = keyboardImage.getRGB(x, y)
                assertNotEquals(background, keyboardPixel, "$case keyboard ring must extend beyond bounds")
                button.performMouseInput { moveTo(center) }
                assertEquals(keyboardPixel, captureToImage().toAwtImage().getRGB(x, y), "$case hover retains ring")
                button.performMouseInput {
                    press()
                    release()
                    exit()
                }
                assertEquals(background, captureToImage().toAwtImage().getRGB(x, y), "$case click hides ring")
            }
        }
    }

    @Test
    fun `fields expose active focus for a mouse in every kit and theme`() {
        focusCases().filter { it.style == HbVisualStyle.Glass }.forEach { case ->
            runSkikoComposeUiTest(size = Size(420f, 260f)) {
                setContent { FocusHost(case) }
                val field = onNodeWithTag("field")
                val bounds = field.fetchSemanticsNode().boundsInRoot
                val x = bounds.center.x.toInt()
                val y = if (case.platform == PlatformUi.MacOs) (bounds.top - 2).toInt() else (bounds.bottom - 1).toInt()
                val idle = captureToImage().toAwtImage().getRGB(x, y)
                field.performMouseInput {
                    moveTo(center)
                    press()
                    release()
                    exit()
                }
                field.assertIsFocused()
                assertNotEquals(idle, captureToImage().toAwtImage().getRGB(x, y), "$case field focus")
            }
        }
    }

    @Test
    fun `former soft style leaves no shadow outside controls or fields`() {
        listOf(false, true).forEach { isDark ->
            runSkikoComposeUiTest(size = Size(420f, 260f)) {
                val case = FocusCase(PlatformUi.MacOs, isDark, HbVisualStyle.Neumorphic)
                setContent { FocusHost(case) }
                val image = captureToImage().toAwtImage()
                listOf("button", "field").forEach { tag ->
                    val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    val x = bounds.center.x.toInt()
                    assertEquals(case.colors.background.toArgb(), image.getRGB(x, (bounds.top - 2).toInt()))
                    assertEquals(case.colors.background.toArgb(), image.getRGB(x, (bounds.bottom + 2).toInt()))
                }
            }
        }
    }
}

private data class FocusCase(val platform: PlatformUi, val isDark: Boolean, val style: HbVisualStyle) {
    val colors: HbColors get() = if (isDark) HbColors.DesktopDark else HbColors.DesktopLight
}

private fun focusCases(): List<FocusCase> = PlatformUi.entries.flatMap { platform ->
    listOf(false, true).flatMap { isDark ->
        listOf(HbVisualStyle.Glass, HbVisualStyle.Platform).map { FocusCase(platform, isDark, it) }
    }
}

@Composable
private fun FocusHost(case: FocusCase) {
    HbTheme(
        darkTheme = case.isDark,
        platformUi = case.platform,
        visualStyle = case.style,
        motion = HbMotion(isReducedMotion = true),
    ) {
        Box(Modifier.fillMaxSize().background(HbTheme.colors.background), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                HbButton("Action", {}, Modifier.width(240.dp).testTag("button"), style = HbButtonStyle.Secondary)
                var value by remember { mutableStateOf("") }
                HbTextField(value, { value = it }, Modifier.width(240.dp).testTag("field"), placeholder = "Search")
            }
        }
    }
}

private fun saveFocusFrame(case: FocusCase, image: BufferedImage) {
    val directory = File("build/reports/desktop-states")
    check(directory.isDirectory || directory.mkdirs())
    check(ImageIO.write(image, "png", File(directory, "${case.platform}-${case.isDark}-${case.style}-focus.png")))
}
