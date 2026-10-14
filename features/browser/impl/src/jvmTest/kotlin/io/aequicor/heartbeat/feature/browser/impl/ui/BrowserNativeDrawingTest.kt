package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class BrowserNativeDrawingTest {
    @Test
    fun `hidden native drawing keeps the real backdrop and restores without remounting or resizing`() =
        runSkikoComposeUiTest(size = Size(80f, 80f)) {
            var isVisible by mutableStateOf(true)
            var mounts = 0
            var disposals = 0
            var measured = IntSize.Zero
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.Blue)) {
                        DisposableEffect(Unit) {
                            mounts++
                            onDispose { disposals++ }
                        }
                        Box(
                            Modifier.size(40.dp).nativeViewDrawing(isVisible)
                                .onSizeChanged { measured = it }
                                .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) },
                        )
                    }
                }
            }
            val clear = captureToImage().toPixelMap()[20, 20]
            assertNotEquals(Color.Blue, clear)
            runOnIdle { isVisible = false }
            assertEquals(Color.Blue, captureToImage().toPixelMap()[20, 20])
            runOnIdle {
                assertEquals(IntSize(40, 40), measured)
                assertEquals(1, mounts)
                assertEquals(0, disposals)
                isVisible = true
            }
            assertEquals(clear, captureToImage().toPixelMap()[20, 20])
            runOnIdle {
                assertEquals(IntSize(40, 40), measured)
                assertEquals(1, mounts)
                assertEquals(0, disposals)
            }
        }
}
