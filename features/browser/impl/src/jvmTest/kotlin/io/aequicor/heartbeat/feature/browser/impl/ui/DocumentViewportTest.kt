package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.components.LocalHbNativeContentViewport
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DocumentViewportTest {
    @Test
    fun `unobstructed document uses its current clip on the first layout and after content resizes`() {
        for (scale in listOf(1f, 2f)) {
            runSkikoComposeUiTest(size = Size(600f * scale, 700f * scale)) {
                var height by mutableStateOf(300.dp)
                val layouts = mutableListOf<Pair<Rect, Rect>>()
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(scale),
                        LocalHbNativeContentViewport provides null,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            DocumentViewport(Modifier.offset(100.dp, 80.dp).size(400.dp, height)) {
                                Box(
                                    Modifier.onGloballyPositioned {
                                        layouts += it.boundsInWindow(clipBounds = false) to it.boundsInWindow()
                                    },
                                )
                            }
                        }
                    }
                }
                runOnIdle {
                    assertTrue(layouts.isNotEmpty())
                    assertTrue(layouts.all { (full, clipped) -> full == clipped }, "First layouts: $layouts")
                }
                for (nextHeight in listOf(500, 180)) {
                    runOnIdle {
                        layouts.clear()
                        height = nextHeight.dp
                    }
                    runOnIdle {
                        assertTrue(layouts.isNotEmpty())
                        assertTrue(layouts.all { (full, clipped) -> full == clipped }, "Resized layouts: $layouts")
                        assertEquals(Rect(100f, 80f, 500f, 80f + nextHeight) * scale, layouts.last().first)
                    }
                }
            }
        }
    }

    @Test
    fun `document without a separate viewport respects its ancestor clip from the first layout`() {
        for (scale in listOf(1f, 2f)) {
            runSkikoComposeUiTest(size = Size(600f * scale, 700f * scale)) {
                val layouts = mutableListOf<Pair<Rect, Rect>>()
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(scale),
                        LocalHbNativeContentViewport provides null,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.size(300.dp, 220.dp).clipToBounds()) {
                                DocumentViewport(Modifier.offset(100.dp, (-40).dp).size(300.dp, 220.dp)) {
                                    Box(
                                        Modifier.onGloballyPositioned {
                                            layouts += it.boundsInWindow(clipBounds = false) to it.boundsInWindow()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                runOnIdle {
                    assertTrue(layouts.isNotEmpty())
                    for ((full, clipped) in layouts) {
                        assertEquals(Rect(100f, -40f, 400f, 180f) * scale, full)
                        assertEquals(Rect(100f, 0f, 300f, 180f) * scale, clipped)
                    }
                }
            }
        }
    }

    @Test
    fun `window intersection becomes an inward rounded offset in Compose pixels`() {
        assertEquals(
            IntRect(21, 41, 380, 480),
            documentViewportBounds(Rect(10f, -20f, 410f, 580f), Rect(30.2f, 20.2f, 390.8f, 460.8f), 1f),
        )
        assertEquals(IntRect.Zero, documentViewportBounds(Rect(0f, 0f, 400f, 600f), Rect(500f, 0f, 800f, 700f), 1f))
    }

    @Test
    fun `final native outward rounding stays inside fractional host edges at each density`() {
        val bounds = Rect(10.5f, -20.75f, 410.5f, 580.25f)
        val visible = Rect(30.2f, 20.2f, 390.8f, 460.8f)
        val root = Offset(0.3f, 1.25f)
        for (density in listOf(1f, 1.25f, 2f)) {
            val clip = documentViewportBounds(bounds, visible, density, root)
            val native = Rect(
                floor((bounds.left + clip.left - root.x) / density) * density + root.x,
                floor((bounds.top + clip.top - root.y) / density) * density + root.y,
                ceil((bounds.left + clip.right - root.x) / density) * density + root.x,
                ceil((bounds.top + clip.bottom - root.y) / density) * density + root.y,
            )
            assertTrue(native.left >= visible.left && native.top >= visible.top, "$density: $native outside $visible")
            assertTrue(
                native.right <= visible.right && native.bottom <= visible.bottom,
                "$density: $native outside $visible",
            )
        }
    }

    @Test
    fun `native child retains its full footprint while Compose clips both axes at each density`() {
        for (scale in listOf(1f, 2f)) {
            runSkikoComposeUiTest(size = Size(600f * scale, 700f * scale)) {
                var viewport by mutableStateOf(Rect(120f, 80f, 460f, 380f) * scale)
                var full = Rect.Zero
                var visible = Rect.Zero
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(scale),
                        LocalHbNativeContentViewport provides viewport,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            DocumentViewport(Modifier.offset(100.dp, (-100).dp).size(400.dp, 600.dp)) {
                                Box(
                                    Modifier.onGloballyPositioned {
                                        full = it.boundsInWindow(clipBounds = false)
                                        visible = it.boundsInWindow()
                                    },
                                )
                            }
                        }
                    }
                }
                runOnIdle {
                    assertEquals(Rect(100f, -100f, 500f, 500f) * scale, full)
                    assertEquals(viewport, visible)
                }
                runOnIdle { viewport = Rect(100f, 0f, 500f, 460f) * scale }
                runOnIdle {
                    assertEquals(Rect(100f, -100f, 500f, 500f) * scale, full)
                    assertEquals(viewport, visible)
                }
                runOnIdle { viewport = Rect.Zero }
                runOnIdle { assertEquals(Rect.Zero, visible) }
            }
        }
    }
}

private operator fun Rect.times(scale: Float) = Rect(left * scale, top * scale, right * scale, bottom * scale)
