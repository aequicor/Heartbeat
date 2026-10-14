package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class HbNativeContentViewportTest {
    @Test
    fun `overlay insets are excluded within the clipped parent on both axes`() {
        val bounds = Rect(40f, 100f, 640f, 900f)
        assertEquals(
            Rect(80f, 220f, 600f, 760f),
            nativeContentViewport(
                bounds,
                Rect(40f, 160f, 600f, 900f),
                Rect(80f, 0f, 650f, 880f),
                topInset = 120f,
                bottomInset = 140f,
            ),
        )
    }

    @Test
    fun `ancestor clipping does not shift the origin of overlay insets`() {
        assertEquals(
            Rect(0f, 100f, 300f, 400f),
            nativeContentViewport(
                Rect(0f, -100f, 300f, 600f),
                Rect(0f, 50f, 300f, 500f),
                parent = null,
                topInset = 200f,
                bottomInset = 200f,
            ),
        )
    }

    @Test
    fun `initial layout fully obstructed hosts and disjoint parents hide native content`() {
        assertEquals(Rect.Zero, nativeContentViewport(Rect.Zero, Rect.Zero, null, 20f, 40f))
        val bounds = Rect(0f, 0f, 300f, 200f)
        assertEquals(Rect.Zero, nativeContentViewport(bounds, bounds, null, 120f, 100f))
        assertEquals(Rect.Zero, nativeContentViewport(bounds, bounds, Rect.Zero, 0f, 0f))
        assertEquals(
            Rect.Zero,
            nativeContentViewport(bounds, bounds, Rect(400f, 0f, 800f, 200f), 0f, 0f),
        )
    }
}
