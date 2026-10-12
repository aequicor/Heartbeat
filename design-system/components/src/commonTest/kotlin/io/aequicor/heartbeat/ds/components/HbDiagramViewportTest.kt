package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HbDiagramViewportTest {
    @Test
    fun `opening fits and resizing preserves the fitted view`() {
        val state = HbDiagramViewportState(Size(2000f, 1000f))
        state.resize(Size(800f, 600f))
        assertEquals(0.4f, state.scale)
        assertEquals(Offset(0f, 100f), state.offset)
        state.resize(Size(400f, 400f))
        assertEquals(0.2f, state.scale)
        assertEquals(Offset(0f, 100f), state.offset)
    }

    @Test
    fun `zoom anchors the image point under the cursor and clamps pan at edges`() {
        val state = HbDiagramViewportState(Size(2000f, 1000f))
        state.resize(Size(800f, 600f))
        val cursor = Offset(300f, 250f)
        val point = (cursor - state.offset) / state.scale
        state.zoom(1f, cursor)
        assertEquals(point, (cursor - state.offset) / state.scale)
        state.pan(Offset(-100000f, -100000f))
        assertEquals(Offset(-1200f, -400f), state.offset)
        assertEquals(1f, state.horizontal.position)
        state.horizontal.scrollTo(0f)
        assertEquals(0f, state.offset.x)
    }

    @Test
    fun `small images center and oversized images can fit below ten percent`() {
        val small = HbDiagramViewportState(Size(40f, 20f))
        small.resize(Size(800f, 600f))
        small.pan(Offset(200f, 300f))
        assertEquals(Offset(380f, 290f), small.offset)
        small.zoom(100f)
        assertEquals(8f, small.scale)
        val big = HbDiagramViewportState(Size(20000f, 10000f))
        big.resize(Size(400f, 300f))
        assertEquals(0.02f, big.minimumScale)
        big.zoom(0.001f)
        assertEquals(0.02f, big.scale)
    }

    @Test
    fun `actions decode UTF8 once and reject malformed or extra parameters`() {
        assertEquals(
            HbDiagramAction(HbDiagramActionKind.Insert, "Привет\nA+B&x"),
            parseDiagramAction("heartbeat://diagram/insert?text=%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82%0AA%2BB%26x"),
        )
        assertEquals("%20", parseDiagramAction("heartbeat://diagram/send?text=%2520")?.text)
        listOf(
            "heartbeat://diagram/send?text=x&session=other",
            "heartbeat://diagram/send?text=%FF",
            "heartbeat://diagram/send?text=%",
            "heartbeat://diagram/send?text=%00",
            "javascript:alert(1)",
            "heartbeat://diagram/run?text=x",
            "heartbeat://diagram/send?text=",
            "heartbeat://diagram/send?text=%20",
        ).forEach { assertNull(parseDiagramAction(it), it) }
        assertTrue(parseDiagramAction("heartbeat://diagram/send?text=hello") != null)
    }
}
