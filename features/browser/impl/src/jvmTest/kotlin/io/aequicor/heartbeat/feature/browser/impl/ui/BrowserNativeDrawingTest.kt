package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BrowserNativeDrawingTest {
    @Test
    fun `kept backing paints its surface over interop clear and leaves the surroundings untouched`() =
        runSkikoComposeUiTest(size = Size(80f, 80f)) {
            var measured = IntSize.Zero
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.Blue)) {
                        Box(
                            Modifier.size(40.dp).nativeViewBacking(Color.Green)
                                .onSizeChanged { measured = it }
                                .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) },
                        )
                    }
                }
            }
            val pixels = captureToImage().toPixelMap()
            assertEquals(Color.Green, pixels[20, 20])
            assertEquals(Color.Blue, pixels[60, 60])
            runOnIdle { assertEquals(IntSize(40, 40), measured) }
        }
}
