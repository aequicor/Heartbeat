package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class HbWindowChromeTest {
    @Test
    fun `mac caption only excludes the sidebar and clears content below it`() {
        assertEquals(96f to 0f, insets(Rect(0f, 0f, 264f, 44f), left = 96f))
        assertEquals(0f to 0f, insets(Rect(264f, 0f, 1280f, 44f), left = 96f))
        assertEquals(0f to 0f, insets(Rect(0f, 44f, 264f, 88f), left = 96f))
    }

    @Test
    fun `windows caption belongs only to the last split pane`() {
        assertEquals(0f to 0f, insets(Rect(264f, 0f, 772f, 44f), right = 138f))
        assertEquals(0f to 138f, insets(Rect(772f, 0f, 1280f, 44f), right = 138f))
        assertEquals(0f to 138f, insets(Rect(0f, 0f, 1280f, 44f), right = 138f))
    }

    @Test
    fun `closed sidebar and narrow windows keep both physical corners clear`() {
        assertEquals(96f to 138f, captionInsets(Rect(0f, 0f, 420f, 44f), 420f, 44f, 96f, 138f, false))
        assertEquals(46f to 0f, insets(Rect(50f, 0f, 264f, 44f), left = 96f))
        assertEquals(96f to 4f, captionInsets(Rect(0f, 0f, 100f, 44f), 100f, 44f, 96f, 138f, false))
    }

    @Test
    fun `fullscreen and absent native caption do not reserve space`() {
        val bounds = Rect(0f, 0f, 1280f, 44f)
        assertEquals(0f to 0f, captionInsets(bounds, 1280f, 44f, 96f, 138f, true))
        assertEquals(0f to 0f, captionInsets(bounds, 1280f, 0f, 0f, 0f, false))
        assertEquals(0f to 0f, insets(Rect.Zero, left = 96f))
    }

    @Test
    fun `display scale changes preserve logical exclusion geometry`() {
        for (scale in listOf(1f, 1.5f, 2f)) {
            val bounds = Rect(0f, 0f, 1280f * scale, 44f * scale)
            val actual = captionInsets(bounds, 1280f * scale, 44f * scale, 96f * scale, 138f * scale, false)
            assertEquals(96f to 138f, actual.first / scale to actual.second / scale)
        }
    }

    private fun insets(bounds: Rect, left: Float = 0f, right: Float = 0f): Pair<Float, Float> =
        captionInsets(bounds, 1280f, 44f, left, right, false)
}
