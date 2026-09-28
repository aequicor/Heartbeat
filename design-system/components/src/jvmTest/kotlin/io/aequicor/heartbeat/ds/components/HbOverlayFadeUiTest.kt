package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbOverlayFadeUiTest {
    @Test
    fun `light transcript fades beneath measured overlays while its center and action remain opaque`() {
        verifyOverlayFades(isDark = false)
    }

    @Test
    fun `dark transcript fades beneath measured overlays while its center and action remain opaque`() {
        verifyOverlayFades(isDark = true)
    }

    private fun verifyOverlayFades(isDark: Boolean) = runSkikoComposeUiTest(size = Size(360f, 420f)) {
        val state = LazyListState(firstVisibleItemIndex = 8)
        var insets by mutableStateOf(PaddingValues(top = 96.dp, bottom = 112.dp))
        var clicks = 0
        setContent { OverlayFadeFixture(isDark, state, insets, onAction = { clicks++ }) }
        runOnIdle {
            assertTrue(state.canScrollBackward && state.canScrollForward)
            assertEquals(420, state.layoutInfo.viewportSize.height, "Fades must preserve the full viewport")
        }
        val colors = if (isDark) HbColors.DesktopDark else HbColors.DesktopLight
        val actionBounds = onNodeWithTag("overlay-action").fetchSemanticsNode().boundsInRoot
        val actionX = (actionBounds.left + 6f).roundToInt()
        val actionY = actionBounds.center.y.roundToInt()
        val before = captureToImage().toAwtImage()
        assertOverlayFadeBands(before, colors, topInset = 96, bottomInset = 112)
        assertPixelNear(before, actionX, actionY, colors.primary.toArgb(), "Floating action must not share the mask")

        runOnIdle { insets = PaddingValues(top = 64.dp, bottom = 160.dp) }
        val after = captureToImage().toAwtImage()
        assertOverlayFadeBands(after, colors, topInset = 64, bottomInset = 160)
        assertPixelNear(
            after,
            actionX,
            actionY,
            colors.primary.toArgb(),
            "Resizing overlays must not fade their actions",
        )
        val centerX = after.width / 2
        assertPixelNear(after, centerX, 80, colors.textPrimary.toArgb(), "The previous top fade must be cleared")
        assertTrue(
            pixelDistance(before.getRGB(centerX, 276), after.getRGB(centerX, 276)) > 20,
            "A growing bottom overlay must move its fade into the previously opaque content",
        )
        onNodeWithTag("overlay-action").performClick()
        runOnIdle { assertEquals(1, clicks) }
    }
}

@Composable
private fun OverlayFadeFixture(isDark: Boolean, state: LazyListState, insets: PaddingValues, onAction: () -> Unit) {
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
        HbTheme(darkTheme = isDark, motion = HbMotion(isReducedMotion = true)) {
            // Equal neighboring blocks create an uninterrupted ink field for sampling alpha, without Haze blur.
            val ink = HbTheme.colors.textPrimary
            Box(Modifier.fillMaxSize().background(HbTheme.colors.background)) {
                HbStickyHeaderHost(
                    state = state,
                    stickyHeaderKeyPrefix = "unused:",
                    header = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    overlapInsets = insets,
                ) {
                    HbLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = state,
                        gap = HbTheme.spacing.none,
                        contentPadding = PaddingValues(),
                        showScrollbar = false,
                    ) {
                        items(40, key = { it }) {
                            Box(Modifier.fillMaxWidth().height(48.dp).background(ink))
                        }
                    }
                }
                HbButton(
                    text = "Overlay",
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("overlay-action"),
                )
            }
        }
    }
}

private fun assertOverlayFadeBands(image: BufferedImage, colors: HbColors, topInset: Int, bottomInset: Int) {
    val x = image.width / 2
    val fade = HbDimensions().transcriptEdgeFade.value.roundToInt()
    val backdrop = colors.background.toArgb()
    val ink = colors.textPrimary.toArgb()
    val bottomStart = image.height - bottomInset
    assertPixelNear(image, x, 4, backdrop, "Content above the top fade must be transparent")
    assertPixelNear(image, x, topInset - fade - 4, backdrop, "No ink may leak above the measured top panel")
    assertPixelNear(image, x, topInset + 4, ink, "Content below the top panel must remain opaque")
    assertPixelNear(image, x, (topInset + bottomStart) / 2, ink, "The open center must remain fully opaque")
    assertPixelNear(image, x, bottomStart - 4, ink, "Content above the bottom panel must remain opaque")
    assertPixelNear(image, x, bottomStart + fade + 4, backdrop, "Content beneath the bottom fade must be transparent")
    assertPixelNear(image, x, image.height - 4, backdrop, "No ink may leak below the measured bottom panel")
    assertOpacity(image, topInset - fade * 3 / 4, colors, 0.25, "Top fade must reveal content gradually")
    assertOpacity(image, topInset - fade / 2, colors, 0.5, "Top fade midpoint")
    assertOpacity(image, topInset - fade / 4, colors, 0.75, "Top fade must approach full opacity")
    assertOpacity(image, bottomStart + fade / 4, colors, 0.75, "Bottom fade must begin with visible content")
    assertOpacity(image, bottomStart + fade / 2, colors, 0.5, "Bottom fade midpoint")
    assertOpacity(image, bottomStart + fade * 3 / 4, colors, 0.25, "Bottom fade must hide content gradually")
}

private fun assertPixelNear(image: BufferedImage, x: Int, y: Int, expected: Int, reason: String) {
    val difference = pixelDistance(image.getRGB(x, y), expected)
    assertTrue(difference <= 3, "$reason: RGB distance=$difference at ($x, $y)")
}

private fun assertOpacity(image: BufferedImage, y: Int, colors: HbColors, expected: Double, reason: String) {
    val backdrop = colors.background.toArgb()
    val ink = colors.textPrimary.toArgb()
    val opacity = pixelDistance(image.getRGB(image.width / 2, y), backdrop).toDouble() / pixelDistance(ink, backdrop)
    assertTrue(abs(opacity - expected) <= 0.08, "$reason: opacity=$opacity, expected=$expected at y=$y")
}

private fun pixelDistance(first: Int, second: Int): Int = abs((first shr 16 and 255) - (second shr 16 and 255)) +
    abs((first shr 8 and 255) - (second shr 8 and 255)) +
    abs((first and 255) - (second and 255))
