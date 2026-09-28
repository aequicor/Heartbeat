package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import io.aequicor.heartbeat.ds.theme.HbTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbIconGeometryTest {
    @Test
    fun `every icon leaves room for its stroke inside the viewport`() = runSkikoComposeUiTest(
        size = Size(1120f, 1600f),
    ) {
        val previewBackground = mutableStateOf(true)
        setContent {
            HbTheme {
                Column(
                    modifier = Modifier.fillMaxSize().background(
                        if (previewBackground.value) HbTheme.colors.background else Color.Transparent,
                    ),
                ) {
                    HbIcons.All.chunked(10).forEach { row ->
                        Row(Modifier.padding(bottom = HbTheme.spacing.m)) {
                            row.forEach { icon ->
                                Column(
                                    modifier = Modifier.width(HbTheme.dimensions.iconTileWidth),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    HbIcon(
                                        icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(HbTheme.dimensions.iconLargeSize * 3)
                                            .testTag(icon.name),
                                    )
                                    HbText(icon.name, style = HbTheme.typography.caption)
                                }
                            }
                        }
                    }
                }
            }
        }
        saveGeometryPreview(captureToImage().toAwtImage())
        runOnIdle { previewBackground.value = false }
        HbIcons.All.forEach { icon ->
            val pixels = onNodeWithTag(icon.name).captureToImage().toPixelMap()
            val lastX = pixels.width - 1
            val lastY = pixels.height - 1
            val border = (0..lastX).map { x -> maxOf(pixels[x, 0].alpha, pixels[x, lastY].alpha) } +
                (0..lastY).map { y -> maxOf(pixels[0, y].alpha, pixels[lastX, y].alpha) }
            assertTrue(border.all { it < 0.05f }, "${icon.name} has a stroke touching the viewport edge")
        }
    }

    @Test
    fun `balanced symbols stay symmetric and directional icons mirror in RTL`() = runSkikoComposeUiTest(
        size = Size(1100f, 200f),
    ) {
        val symmetric = listOf(HbIcons.Settings, HbIcons.Wifi, HbIcons.Heart, HbIcons.Star, HbIcons.MoreVertical)
        val directional = listOf(HbIcons.Undo, HbIcons.Redo, HbIcons.Reply, HbIcons.ArrowLeft, HbIcons.ChevronRight)
        setContent {
            HbTheme {
                Column {
                    Row {
                        symmetric.forEach { icon -> GeometryIcon(icon, icon.name) }
                    }
                    Row {
                        directional.forEach { icon ->
                            GeometryIcon(icon, "${icon.name}-ltr")
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                GeometryIcon(icon, "${icon.name}-rtl")
                            }
                        }
                    }
                }
            }
        }
        symmetric.forEach { icon ->
            val pixels = onNodeWithTag(icon.name).captureToImage().toPixelMap()
            assertMirroredAlpha(pixels, pixels, icon.name)
        }
        directional.forEach { icon ->
            val ltr = onNodeWithTag("${icon.name}-ltr").captureToImage().toPixelMap()
            val rtl = onNodeWithTag("${icon.name}-rtl").captureToImage().toPixelMap()
            assertMirroredAlpha(ltr, rtl, "${icon.name} RTL")
        }
    }
}

@androidx.compose.runtime.Composable
private fun GeometryIcon(icon: ImageVector, tag: String) {
    HbIcon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(HbTheme.dimensions.iconLargeSize * 3).testTag(tag),
    )
}

private fun assertMirroredAlpha(
    first: androidx.compose.ui.graphics.PixelMap,
    second: androidx.compose.ui.graphics.PixelMap,
    label: String,
) {
    var difference = 0f
    var ink = 0f
    for (y in 0 until first.height) {
        for (x in 0 until first.width) {
            val alpha = first[x, y].alpha
            difference += abs(alpha - second[first.width - 1 - x, y].alpha)
            ink += alpha
        }
    }
    assertTrue(difference / ink < 0.035f, "$label is visibly asymmetric: ${difference / ink}")
}

private fun saveGeometryPreview(image: java.awt.image.BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs())
    check(ImageIO.write(image, "png", File(directory, "icons-geometry.png")))
}
