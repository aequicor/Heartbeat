package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class HbEdgeSurfaceUiTest {
    @Test
    fun `an edge panel draws its hairline only on the start and bottom sides over its content`() =
        verifyEdgeSurface(LayoutDirection.Ltr)

    @Test
    fun `an edge panel mirrors its hairline and corner in right to left layouts`() =
        verifyEdgeSurface(LayoutDirection.Rtl)

    /**
     * At density 4 the 1dp hairline is 4px and the 16dp corner 64px, so samples sit well clear of antialiasing.
     * The panel occupies x in 200..399 (LTR) or 0..199 (RTL) and y in 0..119 of a 400x240 window.
     */
    private fun verifyEdgeSurface(direction: LayoutDirection) = runSkikoComposeUiTest(size = Size(400f, 240f)) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(4f), LocalLayoutDirection provides direction) {
                HbTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().background(HbTheme.colors.primary)) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(50.dp, 30.dp)
                                .hbEdgeSurface(HbTheme.colors.surface, RoundedCornerShape(bottomStart = 16.dp)),
                        ) {
                            // Opaque content proves that the hairline is drawn above it.
                            Box(Modifier.fillMaxSize().background(HbTheme.colors.background))
                        }
                    }
                }
            }
        }
        val colors = HbColors.DesktopLight
        val image = captureToImage().toAwtImage()
        val isRtl = direction == LayoutDirection.Rtl

        // Mirrors a left-to-right x coordinate for the right-to-left layout.
        fun x(ltr: Int) = if (isRtl) 399 - ltr else ltr
        val content = colors.background.toArgb()
        assertEquals(content, image.rgb(x(300), 60), "The panel body shows its content")
        assertEquals(content, image.rgb(x(399), 60), "The end edge continues the container without a hairline")
        assertEquals(content, image.rgb(x(300), 0), "The top edge continues the container without a hairline")
        assertNotEquals(content, image.rgb(x(201), 60), "The start side has a hairline over the content")
        assertNotEquals(content, image.rgb(x(300), 118), "The bottom side has a hairline over the content")
        // 66px from the corner's centre: inside the stroke's outer half, outside the 64px rounded fill.
        assertEquals(
            colors.primary.toArgb(),
            image.rgb(x(217), 102),
            "The rounded corner must not draw the hairline's outer half over the container",
        )
        assertEquals(colors.primary.toArgb(), image.rgb(x(100), 60), "The panel leaves the rest of the container")
    }
}

private fun BufferedImage.rgb(x: Int, y: Int): Int = getRGB(x, y)
