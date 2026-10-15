package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbEdgeSurfaceUiTest {
    @Test
    fun `an edge panel draws its hairline only on the start and bottom sides over its content`() {
        for (isDark in listOf(false, true)) verifyEdgeSurface(LayoutDirection.Ltr, isDark)
    }

    @Test
    fun `an edge panel mirrors its hairline and corner in right to left layouts`() {
        for (isDark in listOf(false, true)) verifyEdgeSurface(LayoutDirection.Rtl, isDark)
    }

    /**
     * At density 4 the 1dp hairline is 4px and the 16dp corner 64px, so samples sit well clear of antialiasing.
     * The panel occupies x in 200..399 (LTR) or 0..199 (RTL) and y in 0..119 of a 400x240 window; in LTR its corner
     * arc is centred at (264, 56).
     */
    private fun verifyEdgeSurface(direction: LayoutDirection, isDark: Boolean) = runSkikoComposeUiTest(
        size = Size(400f, 240f),
    ) {
        lateinit var colors: HbColors
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(4f), LocalLayoutDirection provides direction) {
                HbTheme(darkTheme = isDark) {
                    colors = HbTheme.colors
                    Box(Modifier.fillMaxSize().background(HbTheme.colors.primary)) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(50.dp, 30.dp)
                                .hbEdgeSurface(HbTheme.colors.textPrimary, CornerSize(16.dp)),
                        ) {
                            // Opaque content over the upper half proves that the hairline is drawn above it;
                            // the lower half shows the panel's own fill.
                            Box(Modifier.fillMaxWidth().fillMaxHeight(0.5f).background(HbTheme.colors.background))
                        }
                    }
                }
            }
        }
        val image = captureToImage().toAwtImage()
        val isRtl = direction == LayoutDirection.Rtl
        val theme = if (isDark) "dark" else "light"

        // Mirrors a left-to-right x coordinate for the right-to-left layout.
        fun x(ltr: Int) = if (isRtl) 399 - ltr else ltr
        val content = colors.background.toArgb()
        val fill = colors.textPrimary.toArgb()
        val overContent = colors.outlineSubtle.compositeOver(colors.background).toArgb()
        val overFill = colors.outlineSubtle.compositeOver(colors.textPrimary).toArgb()
        val container = colors.primary.toArgb()
        assertPixelNear(image, x(300), 30, content, "$theme: the panel shows its content")
        assertPixelNear(image, x(300), 90, fill, "$theme: the panel fills the area its content leaves")
        assertPixelNear(image, x(399), 30, content, "$theme: the end edge continues the container without a hairline")
        assertPixelNear(image, x(399), 90, fill, "$theme: the end edge has no hairline beside the fill either")
        assertPixelNear(image, x(300), 0, content, "$theme: the top edge continues the container without a hairline")
        assertPixelNear(image, x(201), 30, overContent, "$theme: the start side has a hairline over the content")
        assertPixelNear(image, x(300), 118, overFill, "$theme: the bottom side has a hairline")
        assertPixelNear(image, x(399), 118, overFill, "$theme: the bottom hairline reaches the end edge")
        // 61.5px from the arc's centre: inside the stroke's inner half along the rounded corner.
        assertPixelNear(image, x(220), 99, overFill, "$theme: the rounded corner keeps its hairline")
        // 65.8px from the arc's centre: inside the stroke's outer half, outside the 64px rounded fill.
        assertPixelNear(
            image,
            x(217),
            102,
            container,
            "$theme: the rounded corner must not draw the hairline's outer half over the container",
        )
        assertPixelNear(image, x(100), 60, container, "$theme: the panel leaves the rest of the container")
    }
}

private fun assertPixelNear(image: BufferedImage, x: Int, y: Int, expected: Int, reason: String) {
    val actual = image.getRGB(x, y)
    val distance = listOf(16, 8, 0).maxOf { shift -> abs((actual shr shift and 0xFF) - (expected shr shift and 0xFF)) }
    assertTrue(distance <= 3, "$reason: RGB distance=$distance at ($x, $y)")
}
