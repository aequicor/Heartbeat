package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HbHoverTooltipTest {
    @Test
    fun `browser hint allows clicks inside a selection container and disappears on exit`() {
        for (width in listOf(420, 1280)) {
            for (isDark in listOf(false, true)) checkBrowserHint(width, isDark)
        }
    }

    private fun checkBrowserHint(width: Int, isDark: Boolean) =
        runSkikoComposeUiTest(size = Size(width.toFloat(), 180f)) {
            var clicks = 0
            setContent {
                HbTheme(darkTheme = isDark, motion = HbMotion(tooltipDelayMillis = 0)) {
                    SelectionContainer {
                        Box(Modifier.padding(24.dp)) {
                            HbHoverTooltip("Full conversation title") {
                                HbButton("Open", { clicks++ }, Modifier.testTag("open"))
                            }
                        }
                    }
                }
            }
            val action = onNodeWithTag("open")
            action.performMouseInput { moveTo(center) }
            waitUntil { onAllNodesWithText("Full conversation title").fetchSemanticsNodes().isNotEmpty() }
            val preview = File("build/test-previews/browser-tooltip-$width-$isDark.png")
            check(preview.parentFile.isDirectory || preview.parentFile.mkdirs())
            check(ImageIO.write(captureToImage().toAwtImage(), "png", preview))
            action.performMouseInput {
                press()
                release()
                exit()
            }
            runOnIdle { assertEquals(1, clicks) }
            onAllNodesWithText("Full conversation title").assertCountEquals(0)
        }

    @Test
    fun `browser hint clamps horizontally and flips above a bottom anchor`() {
        val position = HoverTooltipPosition(gap = 4)
        val viewport = IntSize(200, 100)
        val popup = IntSize(80, 20)
        assertEquals(
            IntOffset(0, 34),
            position.calculatePosition(IntRect(0, 10, 20, 30), viewport, LayoutDirection.Ltr, popup),
        )
        assertEquals(
            IntOffset(120, 46),
            position.calculatePosition(IntRect(180, 70, 200, 90), viewport, LayoutDirection.Rtl, popup),
        )
    }
}
