package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class HbDiagramImageCacheTest {
    private val style = HbDiagramStyle(
        Color.Black,
        Color.Gray,
        Color.Gray,
        Color.White,
        Color.Gray,
        Color.LightGray,
        fontSize = 14f,
        isDark = false,
    )

    private fun request(source: String) = HbDiagramRequest(HbDiagramLanguage.PlantUml, source, style, density = 2f)

    private fun image(side: Int) = HbDiagramResult.Image(ImageBitmap(side, side), DpSize(side.dp, side.dp))

    @Test
    fun `least recently used entries leave first when the count is exceeded`() {
        val cache = HbDiagramImageCache(maxEntries = 2)
        cache.put(request("a"), image(1))
        cache.put(request("b"), image(1))
        cache.put(request("a"), assertNotNull(cache.peek(request("a"))))
        cache.put(request("c"), image(1))
        assertNotNull(cache.peek(request("a")))
        assertNull(cache.peek(request("b")))
        assertNotNull(cache.peek(request("c")))
    }

    @Test
    fun `link metadata counts against the image cache budget`() {
        val cache = HbDiagramImageCache(maxBytes = 100)
        cache.put(request("first"), image(1))
        val linked = image(1).copy(links = persistentListOf(HbDiagramLink(Rect(0f, 0f, 1f, 1f), "x".repeat(100))))
        cache.put(request("linked"), linked)
        assertNull(cache.peek(request("first")))
        assertSame(linked, cache.peek(request("linked")))
    }

    @Test
    fun `the byte budget evicts older images but keeps the newest one`() {
        val cache = HbDiagramImageCache(maxBytes = 100L * 100 * 4)
        cache.put(request("small"), image(50))
        val large = image(200)
        cache.put(request("large"), large)
        assertNull(cache.peek(request("small")))
        assertSame(large, cache.peek(request("large")))
    }
}
